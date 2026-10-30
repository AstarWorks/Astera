package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.match.application.eventbus.listeners.AuditLogListener
import com.astarworks.astera.match.application.port.inbound.IMatchRecorder
import com.astarworks.astera.match.application.port.outbound.IMatchReplayRepository
import com.astarworks.astera.match.application.port.outbound.IPlayerPinService
import com.astarworks.astera.match.application.port.outbound.IRecorderStateProvider
import com.astarworks.astera.match.application.service.InMemoryPlayerPinService
import com.astarworks.astera.match.application.service.replay.InMemoryMatchReplayRepository
import com.astarworks.astera.match.application.service.replay.NoopRecorderStateProvider
import com.astarworks.astera.match.application.service.replay.ReplayCaptureEngine
import com.astarworks.astera.match.application.usecase.pin.PlacePinUseCase
import com.astarworks.astera.match.application.usecase.pin.RemovePinUseCase
import com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase
import com.astarworks.astera.match.application.usecase.spectator.LeaveSpectatorUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.event.MatchStarted

/** Spectator, disconnect/reconnect, pins, audit, snapshot save, replay recording + `/astera match` (original Wave 5e / 5v). */
internal object MatchSessionWiring {

    fun install(env: MatchWiringEnv) {
        val services = env.ctx.services
        val logger = env.ctx.logger
        val registrations = env.ctx.registrations
        val subscriptions = env.ctx.subscriptions
        val scheduledTasks = env.scheduledTasks
        val shutdownHooks = env.shutdownHooks
        val matches = env.matches
        val eventBus = env.eventBus
        val scheduler = env.scheduler
        val playerGateway = env.playerGateway
        val commands = env.commands
        val permService = env.permService
        val facade = env.facade
        val worldGateway = env.worldGateway
        val ports = env.ports

        // ---- Wave 5e: Spectator + PlayerPin + Audit + Inventory snapshot save ----
        val sessionRepo = services.getOrThrow<IPlayerSessionRepository>()
        // Wave 5h (ADR-0079 §6): spectator use cases accept a nullable
        // worldGateway. The earlier `val worldGateway = services.get<IWorldGateway>()`
        // at line ~214 is the canonical one; reuse it here.
        @Suppress("UNUSED_VARIABLE")
        val enterSpectator = EnterSpectatorUseCase(
            sessionRepository = sessionRepo,
            worldGateway = worldGateway,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        @Suppress("UNUSED_VARIABLE")
        val leaveSpectator = LeaveSpectatorUseCase(sessionRepo, worldGateway)

        // Wave 5h (ADR-0079 §5): Disconnect/Reconnect listeners. PlayerQuitServer
        // → phase-aware kick/freeze/forfeit; PlayerJoinedServer → resume in
        // 3-min window or lobby fallback. Requires non-null IWorldGateway — only
        // wire if Core registered one (production-side InMemory default exists).
        if (worldGateway != null) {
            val removeFromMatch =
                com.astarworks.astera.match.application.usecase.match.RemoveFromMatchUseCase(
                    sessionRepo, matches, worldGateway, eventBus, scheduler,
                )
            val applyForfeit =
                com.astarworks.astera.match.application.usecase.match.ApplyForfeitUseCase(
                    removeFromMatch, eventBus, scheduler, matches,
                )
            // Mid-match reconnect: back on the player's own team spawn (not the lobby-side spectator stub),
            // with the same spawn protection a respawn gets, the loaner kit re-armed and the sidebar redrawn.
            val resumeSpawn = services.get<com.astarworks.astera.match.application.service.ISpawnSelector>()?.let { selector ->
                com.astarworks.astera.match.application.usecase.spawn.MatchSpawnUseCase(
            scheduler = scheduler,
                    matchRepository = matches,
                    stageRegistry = env.stages,
                    worldGateway = worldGateway,
                    eventBus = eventBus,
                    spawnSelector = selector,
                )
            }
            val resumeMatch =
                com.astarworks.astera.match.application.usecase.match.ResumeMatchUseCase(
                    sessions = sessionRepo,
                    matches = matches,
                    worldGateway = worldGateway,
                    eventBus = eventBus,
                    scheduler = scheduler,
                    spawn = resumeSpawn,
                    protection = services.get<com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway>(),
                    protectionSeconds = { id ->
                        com.astarworks.astera.match.domain.death.DeathRespawnConfig.parse(env.modeConfigOf(id)).config.spawnProtectionSeconds
                    },
                    afterResume = { player ->
                        services.get<com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener>()?.rearm(player)
                    },
                )
            val forfeitSettler =
                com.astarworks.astera.match.application.eventbus.listeners.ForfeitMatchSettler(
                    matches = matches,
                    end = { matchId, reason -> facade.end(matchId, reason) },
                    cancelCountdown = { matchId ->
                        // Same notice as a countdown that ran out of players; the lobby return follows MatchEnded.
                        matches.findById(matchId)?.teams?.flatMap { it.members }.orEmpty().forEach { member ->
                            playerGateway.sendMessage(
                                member,
                                env.msgRenderer.render(
                                    member,
                                    com.astarworks.astera.core.domain.i18n.MessageKey("astera.match.matchmaking.insufficient-cancelled"),
                                    emptyMap(),
                                ),
                            )
                        }
                        facade.end(matchId, com.astarworks.astera.core.domain.event.EndReason.Cancelled("opponents-left"))
                    },
                )
            val disconnectListener =
                com.astarworks.astera.match.application.eventbus.listeners.DisconnectDuringMatchListener(
                    sessionRepo, matches, removeFromMatch, applyForfeit, eventBus, scheduler,
                    // The stars wiring is installed later (MatchGameplayWiring); resolved when somebody leaves.
                    onLeaving = { matchId, player -> env.stars?.drops?.onLeft(matchId, player) },
                    onCountdownLeft = { matchId, team -> forfeitSettler.onLeftCountdown(matchId, team) },
                )
            val reconnectListener =
                com.astarworks.astera.match.application.eventbus.listeners.ReconnectDuringMatchListener(
                    sessionRepo, resumeMatch, worldGateway,
                )
            // Match membership lives on the backend that hosts matches; a lobby sharing the session store must
            // neither flip a dropped player's claim again nor start its own removal timer for it.
            if (env.ctx.serverRole.hostsMatches) {
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.core.domain.event.PlayerQuitServer::class,
                ) { disconnectListener.handle(it) }
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.core.domain.event.PlayerLeavingForLobby::class,
                ) { disconnectListener.onLeavingForLobby(it) }
                subscriptions += eventBus.subscribe(MatchEnded::class) { disconnectListener.onMatchEnded(it) }
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.match.domain.event.PlayerForfeited::class,
                ) { forfeitSettler.onForfeited(it) }
            }
            // Only a backend that hosts matches resumes anyone: a lobby that merely shares the session store
            // must leave the claim alone (MatchReconnectRouter sends the player on to the game server).
            if (env.ctx.serverRole.hostsMatches) {
                subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerArrivedOnServer::class) {
                    reconnectListener.handle(it)
                }
            }
        }

        val pinService = env.ctx.stateful<IPlayerPinService> { InMemoryPlayerPinService(env.ctx.carriedEventBus) }
        @Suppress("UNUSED_VARIABLE") val placePin = PlacePinUseCase(pinService)
        @Suppress("UNUSED_VARIABLE") val removePin = RemovePinUseCase(pinService)

        // Audit log: AuditLogListener handles each event variant via a method per type.
        val auditRepo = services.getOrThrow<IAuditLogRepository>()
        val auditListener = AuditLogListener(auditRepo)
        subscriptions += eventBus.subscribe(ChatMessageSent::class) { auditListener.handleChatMessageSent(it) }
        subscriptions += eventBus.subscribe(MatchStarted::class) { auditListener.handleMatchStarted(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { auditListener.handleMatchEnded(it) }

        logger.info("Wave 5e wiring complete: Spectator + PlayerPin + AuditLog listeners.")

        // ---- Wave 5v: Match recording capture (ADR-0088) --------------------
        // Repository: registered at priority 0 (in-memory) so an absent
        // Astera-Persistence plugin still produces well-formed RecordedReplay
        // objects (just lost on shutdown). Postgres impl overlays at
        // priority 100 from AsteraPersistencePlugin.
        val replayRepo: IMatchReplayRepository =
            env.ctx.stateful<IMatchReplayRepository> { InMemoryMatchReplayRepository() }

        // State provider: registered at priority 0 (Noop). Wave 6a's
        // PaperRecorderStateProvider overlays at priority 100; in absence,
        // key frames carry empty per-player maps (still well-formed).
        val recorderStateProvider: IRecorderStateProvider =
            env.ctx.stateful<IRecorderStateProvider> { NoopRecorderStateProvider() }

        val recorder = ReplayCaptureEngine(
            bus = eventBus,
            scheduler = scheduler,
            repository = replayRepo,
            stateProvider = recorderStateProvider,
        )
        recorder.bootstrap()
        registrations += services.register<IMatchRecorder>(recorder)
        // onDisable hook: shutdown subscriptions + scheduled tasks owned by
        // the engine (its own list, distinct from this plugin's `subscriptions` /
        // `scheduledTasks` collections so disable order stays predictable).
        shutdownHooks += { recorder.shutdown() }

        logger.info("Wave 5v wiring complete: ReplayCaptureEngine registered as IMatchRecorder.")

        // 5. Mount `/astera match ...` (ADR-0184) -------------------------------
        commands.register(
            MatchCommandSpecs.buildMatchCommandSpec(
                lifecycle = facade,
                matches = matches,
                playerGateway = playerGateway,
                recorder = recorder,
                replayRepo = replayRepo,
                exportRoot = ports.replayExportRoot,
                logger = logger,
                stageIds = { env.stages.all().map { it.id.value } },
            ),
        )
    }
}
