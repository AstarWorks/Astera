package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.match.application.eventbus.listeners.respawn.EliminatedTeamSpectators
import com.astarworks.astera.match.application.service.death.DeathRespawnCoordinator
import com.astarworks.astera.match.application.service.death.SpectatorTargetCycler
import com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase
import com.astarworks.astera.match.application.usecase.spectator.LeaveSpectatorUseCase
import com.astarworks.astera.match.domain.death.DeathRespawnConfig
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationMatchTracker
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.application.service.ISpawnSelector
import com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.core.domain.event.MatchStarted

/** Queue, rating, respawn, HUD, win-condition, lobby intent, admin commands (original Wave 5wireup .. 5admin-ops). */
internal object MatchGameplayWiring {

    /** Queue notifier keys that also show a title (ADR-0248). */
    private val QUEUE_TITLE_BEATS: Set<String> = setOf("found", "tick", "transferring", "match-found")

    fun install(env: MatchWiringEnv) {
        val services = env.ctx.services
        val logger = env.ctx.logger
        val registrations = env.ctx.registrations
        val subscriptions = env.ctx.subscriptions
        val scheduledTasks = env.scheduledTasks
        val matches = env.matches
        val stages = env.stages
        val eventBus = env.eventBus
        val scheduler = env.scheduler
        val playerGateway = env.playerGateway
        val commands = env.commands
        val permService = env.permService
        val msgRenderer = env.msgRenderer
        val matchModeRegistry = env.matchModeRegistry
        val facade = env.facade
        val annihilationTracker = env.annihilationTracker
        val worldGateway = env.worldGateway

        // ── Wave 5wireup (ADR-0116) ──────────────────────────────────────
        //
        // G9: register the queue command spec defined above but never wired.
        //     Includes instantiating + registering the matchmaker itself —
        //     Wave 5w defined `InMemoryMatchmaker` but no production wiring
        //     used it (tests only). Phase 1 PoC defaults: 2 teams, 1 player
        //     per team minimum (= 2 players total).
        // G4: subscribe RespawnOnPlayerKilledListener.
        // G5: subscribe TeleportToLobbyOnMatchEndedListener.
        // ── Wave 7b (ADR-0037): per-player Elo rating + tournament repos ─────
        // Rating drives ranked matchmaking team-balancing (ratingLookup below)
        // and is updated on MatchEnded. Postgres overlays both repos at
        // priority 100 (V47 player_ratings / V46 tournaments).
        val ratingRepository: com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository =
            services.get<com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository>()
                ?: com.astarworks.astera.match.application.service.rating.InMemoryPlayerRatingRepository().also {
                    registrations += services.register<com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository>(it, priority = 0)
                }
        val tournamentRepository: com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository =
            services.get<com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository>()
                ?: com.astarworks.astera.match.application.service.tournament.InMemoryTournamentRepository().also {
                    registrations += services.register<com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository>(it, priority = 0)
                }

        val matchmaker: IMatchmaker =
            services.get<IMatchmaker>()
                ?: InMemoryMatchmaker(
                    eventBus = eventBus,
                    ratingLookup = { ratingRepository.ratingOf(it) },
                    sizeByMode = { env.queueSettings.current().sizeFor(it) },
                ).also {
                    registrations += services.register<IMatchmaker>(it, priority = 0)
                }
        // Role gating (ADR-0179): the queue lives on the lobby; a game-only
        // backend has no queue command, scheduler or lobby intents.
        val role = env.ctx.serverRole
        if (role.hostsLobby) {
            commands.register(
                MatchCommandSpecs.buildQueueCommandSpec(
                    matchmaker = matchmaker,
                    matches = matches,
                    settings = { env.queueSettings.current() },
                ),
            )
        }

        // ── WP8: queue → match. Scheduled tryFormMatch + auto start/entry ──
        // ADR-0248: chat for everything, plus a full-screen title for the beats a player must not miss: the
        // roster is ready ("found"), the last three seconds ("tick"), and the move into the arena.
        val queueHud = services.get<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        val queueNotifier = com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier { id, key, ph ->
            val suffix = key.value.substringAfterLast('.')
            if (suffix != "found" && suffix != "tick") playerGateway.sendMessage(id, env.msgRenderer.render(id, key, ph))
            if (suffix in QUEUE_TITLE_BEATS) {
                queueHud?.showFor(
                    id,
                    com.astarworks.astera.core.domain.hud.HudLayout.Subtitle(
                        component = com.astarworks.astera.core.domain.hud.HudComponent.Text(
                            com.astarworks.astera.core.domain.i18n.MessageKey("astera.play.title.$suffix.subtitle"), placeholders = ph,
                        ),
                        title = com.astarworks.astera.core.domain.hud.HudComponent.Text(
                            com.astarworks.astera.core.domain.i18n.MessageKey("astera.play.title.$suffix.title"), placeholders = ph,
                        ),
                        fadeInTicks = 4,
                        stayTicks = if (suffix == "tick") 14 else 30,
                        fadeOutTicks = 6,
                    ),
                )
            }
        }
        val planFor: (com.astarworks.astera.match.domain.matchmaking.MatchmakingMode) -> com.astarworks.astera.match.application.usecase.matchmaking.MatchPlan = { mode ->
            val lane = env.queueSettings.current().laneFor(mode)
                ?: com.astarworks.astera.match.domain.matchmaking.QueueSettings.DEFAULT.lanes.getValue("norma")
            com.astarworks.astera.match.application.usecase.matchmaking.MatchPlan(lane.stage, lane.modeKey)
        }
        val crossServer = com.astarworks.astera.match.application.service.matchmaking.CrossServerSettings.from(System.getenv())
        var adminFormer: com.astarworks.astera.match.application.usecase.matchmaking.MatchFormer? = null
        if (role.hostsLobby) {
            // ALL: form and play the match right here. LOBBY: hand the formed teams to the game server.
            val matchFormer: com.astarworks.astera.match.application.usecase.matchmaking.MatchFormer =
                if (role.hostsMatches) {
                    com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundUseCase(
                        matchmaker = matchmaker,
                        lifecycle = facade,
                        matches = matches,
                        notifier = queueNotifier,
                        planFor = planFor,
                    )
                } else {
                    com.astarworks.astera.match.application.service.matchmaking.RemoteMatchFormer(
                        matchmaker = matchmaker,
                        messenger = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IProxyMessenger>(),
                        transfers = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IServerTransferGateway>(),
                        liveness = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IServerLiveness>(),
                        notifier = queueNotifier,
                        scheduler = scheduler,
                        settings = crossServer,
                        planFor = planFor,
                    ).also { remote ->
                        val statusSubscription = remote.start()
                        env.shutdownHooks += { statusSubscription.cancel() }
                    }
                }
            adminFormer = matchFormer
            val queueScheduler = com.astarworks.astera.match.application.service.matchmaking.QueueMatchScheduler(
                matchmaker = matchmaker,
                matchFound = matchFormer,
                notifier = queueNotifier,
                scheduler = scheduler,
                settings = { env.queueSettings.current() },
            )
            env.queueProgress = queueScheduler
            scheduledTasks += queueScheduler.start()
        }
        if (role.hostsLobby && !role.hostsMatches) {
            // LOBBY: a login that the game server holds a seat for goes straight back to the running match.
            val router = com.astarworks.astera.match.application.service.matchmaking.MatchReconnectRouter(
                messenger = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IProxyMessenger>(),
                transfers = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IServerTransferGateway>(),
                eventBus = eventBus,
                scheduler = scheduler,
                settings = crossServer,
            )
            router.start()
            env.shutdownHooks += { router.stop() }
        }
        if (!role.hostsLobby) {
            // GAME: accept assignments, receive the players, send them back after the match.
            val receiver = com.astarworks.astera.match.application.service.matchmaking.MatchAssignmentReceiver(
                messenger = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IProxyMessenger>(),
                transfers = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IServerTransferGateway>(),
                lifecycle = facade,
                matches = matches,
                eventBus = eventBus,
                scheduler = scheduler,
                notifier = queueNotifier,
                settings = crossServer,
                waivers = services.get<com.astarworks.astera.match.application.admin.MatchMinPlayerWaivers>(),
                stages = stages,
                worlds = worldGateway,
                hud = queueHud,
            )
            registrations += services.register<com.astarworks.astera.match.application.service.matchmaking.IArrivalSpawns>(receiver, priority = 0)
            receiver.start()
            env.shutdownHooks += { receiver.stop() }
            // A player who drops out of the running match is announced to the lobby, which sends them back
            // here when they log in again (Velocity always lands a fresh login on the lobby).
            val announcer = com.astarworks.astera.match.application.service.matchmaking.MatchReconnectAnnouncer(
                messenger = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IProxyMessenger>(),
                matches = matches,
                eventBus = eventBus,
                scheduler = scheduler,
                settings = crossServer,
            )
            announcer.start()
            env.shutdownHooks += { announcer.stop() }
            // A game process that died mid-match leaves ACTIVE rows in the (shared) match store; they would make
            // every later assignment answer "busy" and swallow in-match deaths of players who are not in them.
            for (stale in matches.findActiveMatches().toList()) {
                matches.save(stale.withPhase(com.astarworks.astera.core.domain.match.MatchPhase.ENDED))
                logger.warning("Closed stale match ${stale.id} left over from a previous game-server run.")
            }
        }
        // A queued player who disconnects leaves the queue (so they are never
        // pulled into a match while offline).
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.PlayerQuitServer::class,
        ) { matchmaker.dequeue(it.playerId) }

        // ── Wave 7b (ADR-0037): ranked rating update + tournament use cases ──
        // On MatchEnded, recompute each participant's Elo (winner ↑ / loser ↓
        // against the opposing team average) and publish PlayerRatingUpdated;
        // Stats subscribes that to materialise the leaderboard. Draws no-op.
        val updateRatingOnMatchEnded = com.astarworks.astera.match.application.usecase.rating.UpdateRatingOnMatchEndedUseCase(
            matchRepository = matches,
            ratingRepository = ratingRepository,
            eventBus = eventBus,
        )
        val updateRatingListener = com.astarworks.astera.match.application.eventbus.listeners.UpdateRatingOnMatchEndedListener(updateRatingOnMatchEnded)
        subscriptions += eventBus.subscribe(MatchEnded::class) { updateRatingListener.handle(it) }

        // Tournament bracket use cases registered for cross-plugin / web / admin
        // command use. In-game admin command surface lands in Wave 7c content
        // tooling; the domain + persistence + events ship here.
        registrations += services.register(
            com.astarworks.astera.match.application.usecase.tournament.CreateTournamentUseCase(tournamentRepository, eventBus),
            priority = 0,
        )
        registrations += services.register(
            com.astarworks.astera.match.application.usecase.tournament.ReportMatchResultUseCase(tournamentRepository, eventBus),
            priority = 0,
        )

        val health = services.getOrThrow<IPlayerHealthGateway>()
        val protectionGateway = services.getOrThrow<ISpawnProtectionGateway>()
        val respawnUseCase = RespawnUseCase(
            healthGateway = health,
            worldGateway = services.getOrThrow<IWorldGateway>(),
            itemRegistry = services.getOrThrow<IItemRegistry>(),
            protectionGateway = protectionGateway,
            eventBus = eventBus,
            scheduler = scheduler,
            statusEffectContainer = services.get<IStatusEffectContainer>(),
        )
        val respawnSelectorPort: ISpawnSelector = services.getOrThrow<ISpawnSelector>()
        val deathHud = services.getOrThrow<IHudGateway>()
        env.ports.spectator?.let { registrations += services.register<ISpectator>(it(services), priority = 100) }
        val spectatorPort = services.get<ISpectator>()
        // The eliminated-team tracker and the cycler reference each other: the cycler skips
        // eliminated players, the tracker focuses the cycler after switching a team to spectating.
        var eliminatedRef: EliminatedTeamSpectators? = null
        val cycler = spectatorPort?.let {
            SpectatorTargetCycler(
                matches = matches,
                spectator = it,
                hud = deathHud,
                players = playerGateway,
                isOut = { match, player -> eliminatedRef?.isEliminated(match, player) == true },
            )
        }
        env.spectatorCycler = cycler
        val sessions = services.get<IPlayerSessionRepository>()
        val eliminatedTeams = EliminatedTeamSpectators(
            matches = matches,
            healthGateway = health,
            spectator = spectatorPort,
            // No world gateway: spectators stay in the arena (IWorldGateway.spectatorSpawn is a lobby stub).
            enterSpectator = sessions?.let {
                EnterSpectatorUseCase(sessionRepository = it, eventBus = eventBus, scheduler = scheduler)
            },
            leaveSpectator = sessions?.let { LeaveSpectatorUseCase(sessionRepository = it) },
            cycler = cycler,
            hud = deathHud,
        )
        eliminatedRef = eliminatedTeams
        subscriptions += eventBus.subscribe(TeamEliminated::class) { eliminatedTeams.onTeamEliminated(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { eliminatedTeams.onMatchEnded(it) }

        // F3-5: death screen -> death cam -> countdown -> respawn at a team spawn -> spawn protection.
        val deathConfigLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val matchStars = MatchStarWiring.install(env)
        env.stars = matchStars
        commands.register(MatchCommandSpecs.buildNormaCommandSpec(matchStars.norma))
        val deathRespawn = DeathRespawnCoordinator(
            matches = matches,
            stages = stages,
            spawnSelector = respawnSelectorPort,
            respawnUseCase = respawnUseCase,
            healthGateway = health,
            protection = protectionGateway,
            scheduler = scheduler,
            hud = deathHud,
            messages = msgRenderer,
            players = playerGateway,
            items = services.get<IItemRegistry>(),
            skills = services.get<com.astarworks.astera.combat.application.port.outbound.ISkillRegistry>(),
            spectator = spectatorPort,
            cycler = cycler,
            eliminatedTeams = eliminatedTeams,
            configOf = { matchId ->
                val parsed = DeathRespawnConfig.parse(env.modeConfigOf(matchId))
                parsed.warnings.filter { deathConfigLogged.add(it) }.forEach { logger.warning(it) }
                parsed.config
            },
        )
        subscriptions += eventBus.subscribe(PlayerKilled::class) { deathRespawn.onKilled(it) }
        subscriptions += eventBus.subscribe(DamageDealt::class) { deathRespawn.onDamageDealt(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { deathRespawn.onMatchEnded(it) }
        subscriptions += eventBus.subscribe(TeamEliminated::class) { deathRespawn.onTeamEliminated(it) }
        // Out-of-match deaths (lobby PvP / fall / mob) restore HP and return
        // the player to lobby spawn; in-match deaths stay with respawnOnKilled.
        val returnToLobbyOnDeath = com.astarworks.astera.match.application.eventbus.listeners.respawn
            .ReturnToLobbyOnOutOfMatchDeathListener(
                matches = matches,
                healthGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway>(),
                worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
                playerGateway = playerGateway,
                messages = msgRenderer,
                statusEffects = services.get<com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer>(),
                gameServer = role == com.astarworks.astera.core.domain.proxy.ServerRole.GAME,
            )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.combat.domain.event.PlayerKilled::class,
        ) { event -> returnToLobbyOnDeath.handle(event) }

        val teleportLobbyListener = com.astarworks.astera.match.application.eventbus.listeners.lobby
            .TeleportToLobbyOnMatchEndedListener(
                matches = matches,
                worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
                eventBus = eventBus,
                scheduler = scheduler,
            )
        // Game-only backends send players back through the proxy instead (MatchAssignmentReceiver).
        if (role != com.astarworks.astera.core.domain.proxy.ServerRole.GAME) {
            subscriptions += eventBus.subscribe(MatchEnded::class) { event ->
                teleportLobbyListener.handle(event)
            }
        }

        // F1-I (ADR-0186): `/astera admin drain` cancels running matches so their players can be moved.
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.lifecycle.ServerDrainStarted::class,
        ) { event ->
            if (!event.abortMatches) return@subscribe
            for (active in matches.findActiveMatches().toList()) {
                facade.end(active.id, com.astarworks.astera.core.domain.event.EndReason.Cancelled("server-drain"))
            }
        }

        logger.info(
            "Wave 5wireup wired: /astera queue command registered; " +
                "RespawnOnPlayerKilled + TeleportToLobbyOnMatchEnded subscribed.",
        )

        // ── Wave K 5integration-wire (ADR-0128) ─────────────────────────────
        //
        //   RecomputeEngagementSnapshotOnMatchEndedListener — refreshes the
        //   per-player engagement snapshot for every participant when a
        //   match ends. Closes the freshness drift between match
        //   conclusion and the 5-minute dashboard scheduler tick.
        val engagementService = services.getOrThrow<
            com.astarworks.astera.core.application.service.engagement.EngagementDashboardService,
        >()
        val recomputeEngagementOnMatchEnded =
            com.astarworks.astera.match.application.eventbus.listeners.engagement
                .RecomputeEngagementSnapshotOnMatchEndedListener(
                    matchRepository = matches,
                    engagement = engagementService,
                )
        subscriptions += eventBus.subscribe(MatchEnded::class) { event ->
            recomputeEngagementOnMatchEnded.handle(event)
        }
        logger.info(
            "Wave K 5integration-wire (ADR-0128) wired: " +
                "RecomputeEngagementSnapshotOnMatchEnded subscribed.",
        )

        // ── Wave 5complete (ADR-0117 §Axis 4 + Axis 5) ──────────────────
        //
        //   MatchHudWiring (sidebar services)    — see ADR-0177
        //   KillfeedOnPlayerKilledListener       — match chat kill feed (ADR-0206) +
        //                                          killer subtitle; victim screen is
        //                                          DeathRespawnCoordinator
        //
        // Sidebar + killfeed talk to the Core IHudGateway (Phase 1 in-memory
        // by default; Wave 6b PaperHudGateway overlays at priority 100).
        val hudGateway: com.astarworks.astera.core.application.port.outbound.IHudGateway =
            services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        // WP14 (ADR-0177): one owner per HUD channel. The match sidebar and the
        // lobby sidebar are the SIDEBAR channel's only producers; the match
        // service renders whatever the match's IGameMode reports.
        MatchHudWiring.install(env, matchmaker, hudGateway)

        val killfeedListener = com.astarworks.astera.match.application.eventbus.listeners.killfeed
            .KillfeedOnPlayerKilledListener(
                matches = matches,
                hudGateway = hudGateway,
                messages = msgRenderer,
                players = playerGateway,
                items = services.get<IItemRegistry>(),
                skills = services.get<com.astarworks.astera.combat.application.port.outbound.ISkillRegistry>(),
            )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.combat.domain.event.PlayerKilled::class,
        ) { killfeedListener.handle(it) }

        // A Norma match is won by reaching the top Norma level (NormaLevelService). This listener ends the match after `time_limit_seconds` of ACTIVE time
        // (content/game-modes/norma.yaml): highest Norma level, then stars carried, then kills (counted by NormaLevelService); still tied is a draw.
        val normaKills = com.astarworks.astera.match.application.eventbus.listeners.victory.NormaTimeLimitListener(
            matches = matches,
            lifecycle = env.facade,
            isNorma = env.normaLayerOf,
            timeLimitTicks = { id -> (env.modeConfigOf(id)["time_limit_seconds"]?.toLongOrNull()?.takeIf { it > 0 } ?: 600L) * 20L },
            now = { scheduler.currentTick() },
            levelOf = { match, team -> matchStars.norma.topLevel(match, team) },
            starsOf = { match, team -> matchStars.norma.carried(match, team) },
        )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.PhaseChanged::class,
        ) { normaKills.onPhaseChanged(it) }
        scheduledTasks += scheduler.runRepeating(
            com.astarworks.astera.core.application.port.outbound.ticks(20),
            com.astarworks.astera.core.application.port.outbound.ticks(20),
        ) { normaKills.tick() }

        logger.info(
            "Wave 5complete wired: KillfeedOnPlayerKilled + " +
                "/astera match {abort,cancel,restart} sub-commands registered.",
        )

        // ── Wave I 5match-wireup-fix (ADR-0126) ─────────────────────────────
        //
        // Round 10 Wave D 5game-complete (ADR-0121) created 4 listener
        // classes (MatchCountdownListener, MatchEndSummaryListener,
        // ApplyPvpDeathPolicyListener, AnnihilationWinConditionListener)
        // but never wired their `eventBus.subscribe` calls. The Round 13
        // audit (commit 6229f0c) caught the gap by failing to end an
        // Annihilation match even after every enemy nexus was broken —
        // AnnihilationMatchTracker.evaluateWinCondition was observable
        // by no one. This block restores the 4 subscriptions, mirroring
        // the cancel-on-disable pattern (Wave 5server-harden ADR-0123 §4).
        //
        // DefaultPvpDeathPolicyResolver is registered here too because
        // ApplyPvpDeathPolicyListener depends on it and no earlier wave
        // landed the registration. Stage YAML overrides remain empty
        // (Wave 5persist will populate via the stage loader).

        val pvpDeathResolver: com.astarworks.astera.match.application.port.outbound.IPvpDeathPolicyResolver =
            services.get<com.astarworks.astera.match.application.port.outbound.IPvpDeathPolicyResolver>()
                ?: com.astarworks.astera.match.application.service.death.DefaultPvpDeathPolicyResolver(
                    matchRepo = matches,
                    modeRegistry = matchModeRegistry,
                ).also {
                    registrations += services.register<
                        com.astarworks.astera.match.application.port.outbound.IPvpDeathPolicyResolver,
                    >(it, priority = 0)
                }

        val inventoryMutator =
            services.getOrThrow<com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator>()

        val matchCountdownListener =
            com.astarworks.astera.match.application.eventbus.listeners.match
                .MatchCountdownListener(
                    matches = matches,
                    hudGateway = hudGateway,
                    scheduler = scheduler,
                )
        subscriptions += eventBus.subscribe(MatchStarted::class) {
            matchCountdownListener.handle(it)
        }

        // ADR-0256: countdown ticks + go, death cue, last-stretch BGM tension.
        val matchCueListener = com.astarworks.astera.match.application.eventbus.listeners.match.MatchCueListener(
            matches = matches,
            presentation = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.presentation.IPresentation>(),
            bgm = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm>(),
            scheduler = scheduler,
        )
        subscriptions += eventBus.subscribe(MatchStarted::class) { matchCueListener.onStarted(it) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.combat.domain.event.PlayerKilled::class) { matchCueListener.onKilled(it) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.match.domain.event.PhaseChanged::class) { matchCueListener.onPhaseChanged(it) }

        // F3-4: per-match kills / deaths / assists / objectives behind the result screen.
        val matchTally = com.astarworks.astera.match.application.service.result.MatchTallyService(matches)
        subscriptions += eventBus.subscribe(MatchStarted::class) { matchTally.onMatchStarted(it.matchId, it.modeKey.orEmpty()) }
        subscriptions += eventBus.subscribe(com.astarworks.astera.combat.domain.event.PlayerKilled::class) {
            matchTally.onKill(it.victim, it.killer)
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MobKilled::class) {
            matchTally.onMobKill(it.killerId)
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.combat.domain.event.AssistCredited::class) {
            matchTally.onAssist(it.player, it.victim)
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.NexusDestroyed::class) {
            matchTally.onObjective(it.matchId, it.attackerId)
        }

        val matchEndSummaryListener =
            com.astarworks.astera.match.application.eventbus.listeners.match
                .MatchEndSummaryListener(
                    matches = matches,
                    hudGateway = hudGateway,
                    messages = msgRenderer,
                    playerGateway = playerGateway,
                    tally = matchTally,
                    eventBus = eventBus,
                    nameOf = { id -> env.ctx.services.get<IMcServer>()?.findPlayer(id)?.name },
                )
        subscriptions += eventBus.subscribe(MatchEnded::class) {
            matchEndSummaryListener.handle(it)
        }

        val applyPvpDeathPolicyListener =
            com.astarworks.astera.match.application.eventbus.listeners.death
                .ApplyPvpDeathPolicyListener(
                    matches = matches,
                    resolver = pvpDeathResolver,
                    inventoryMutator = inventoryMutator,
                )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.combat.domain.event.PlayerKilled::class,
        ) { applyPvpDeathPolicyListener.handle(it) }

        val annihilationWinConditionListener =
            com.astarworks.astera.match.application.eventbus.listeners.victory
                .AnnihilationWinConditionListener(
                    tracker = annihilationTracker,
                    lifecycle = facade,
                    scheduler = scheduler,
                    eventBus = eventBus,
                )
        // AnnihilationWinConditionListener owns its own `subscribe()` helper
        // that returns both TeamEliminated + AnnihilationPhaseTransitioned
        // subscriptions. Add all returned handles to the cancel list so
        // /reload (Wave 5server-harden ADR-0123 §4) cleans both paths.
        subscriptions += annihilationWinConditionListener.subscribe()

        // ── Reconnect countdown HUD listener (private-beta blocker) ──
        // Subscribes to PlayerDisconnectedFromMatch and per-second ticks
        // an ActionBar showing "Reconnect window: 2:45 left" until either
        // the timeout elapses or PlayerReconnectedToMatch fires.
        val reconnectCountdownHudListener =
            com.astarworks.astera.match.application.eventbus.listeners.hud
                .ReconnectCountdownHudListener(
                    hudGateway = hudGateway,
                    scheduler = scheduler,
                )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.PlayerDisconnectedFromMatch::class,
        ) { reconnectCountdownHudListener.onDisconnected(it) }
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch::class,
        ) { reconnectCountdownHudListener.onReconnected(it) }

        logger.info(
            "Wave I 5match-wireup-fix wired: MatchCountdown + MatchEndSummary + " +
                "ApplyPvpDeathPolicy + AnnihilationWinCondition + ReconnectCountdownHud " +
                "subscribed; DefaultPvpDeathPolicyResolver registered.",
        )

        // ── Wave 5lobby-content (ADR-0119) — `lobby.queue.*` intent bridge ─
        // Lobby compass tile / queue NPC right-click emits `lobby.queue.show`
        // (open mode-list sub-menu) or `lobby.queue.join` with
        // `payload[modeKey] = "norma" | "custom:<id>" | "tournament:<id>"`.
        // The handler resolves the player's party via IPartyRepository for
        // party-aware enqueue (Wave 5w contract).
        if (role.hostsLobby) {
            // ADR-0248: "Play vs bots" asks the runner over Redis; without Redis it answers "unavailable".
            val botRequester = com.astarworks.astera.match.application.service.matchmaking.BotMatchRequester(
                gateway = services.get<com.astarworks.astera.core.application.port.outbound.bots.IBotRequestGateway>()
                    ?: com.astarworks.astera.core.application.port.outbound.bots.UnavailableBotRequestGateway(),
                scheduler = scheduler,
                notifier = queueNotifier,
                nameOf = { playerGateway.nameOf(it) },
            ).also { requester ->
                requester.start()
                env.shutdownHooks += { requester.stop() }
            }
            val lobbyQueueIntentHandler =
                com.astarworks.astera.match.application.intent.LobbyQueueIntentHandler(
                    matchmaker = matchmaker,
                    joiner = com.astarworks.astera.match.application.service.matchmaking.LaneQueueJoiner(
                        matchmaker = matchmaker,
                        parties = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPartyRepository>(),
                        matches = matches,
                    ),
                    settings = { env.queueSettings.current() },
                    guiAdapter = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiAdapter>(),
                    mcServer = services.getOrThrow<IMcServer>(),
                    messages = services.getOrThrow<IMessageRenderer>(),
                    playerGateway = playerGateway,
                    bots = botRequester,
                )
            // Kit / menu tiles swap Play for "Leave queue" (visible_if state:queued) and are rebuilt on every change.
            registrations += services.register<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyQueueState>(
                com.astarworks.astera.core.application.port.outbound.lobby.ILobbyQueueState { player -> matchmaker.queueStatus(player) != null },
                priority = 0,
            )
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchQueueJoined::class) {
                services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyKitRefresher>()?.refresh(it.playerId)
            }
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchQueueLeft::class) {
                services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyKitRefresher>()?.refresh(it.playerId)
            }
            val queueLog = com.astarworks.astera.match.application.service.matchmaking.QueueActivityLogger { playerGateway.nameOf(it) }
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchQueueJoined::class) { queueLog.onJoined(it) }
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchQueueLeft::class) { queueLog.onLeft(it) }
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchFormed::class) { queueLog.onFormed(it) }
            val lobbyQueueIntentRouter =
                services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>()
            registrations += lobbyQueueIntentRouter.register(
                com.astarworks.astera.match.application.intent.LobbyQueueIntentHandler.INTENT_PREFIX,
            ) { intent, player ->
                lobbyQueueIntentHandler.handle(intent, player)
            }

            logger.info(
                "Wave 5lobby-content wired: LobbyQueueIntentHandler registered on " +
                    "'lobby.queue.' prefix.",
            )

            // ── F3-1 — lobby lane pads: step on a pad -> join that lane; hologram counts ──
            // Core builds the pads from this catalog (it cannot read queue.yaml itself) and
            // publishes LobbyLanePadEntered when a player steps on one.
            registrations += services.register<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog>(
                com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog {
                    env.queueSettings.current().lanes.keys.toList()
                },
                priority = 0,
            )
            val lanePadJoin = com.astarworks.astera.match.application.service.matchmaking.LobbyLanePadJoinService(
                matchmaker = matchmaker,
                parties = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPartyRepository>(),
                matches = matches,
                mcServer = services.getOrThrow<IMcServer>(),
                settings = { env.queueSettings.current() },
                board = services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneBoard>(),
            )
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.LobbyLanePadEntered::class) {
                lanePadJoin.onPadEntered(it)
            }
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchQueueJoined::class) { lanePadJoin.refreshBoard() }
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchQueueLeft::class) { lanePadJoin.refreshBoard() }
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchFormed::class) { lanePadJoin.refreshBoard() }
            // Safety net for paths that drain a queue without an event (admin force start, reload).
            scheduledTasks += scheduler.runRepeating(
                com.astarworks.astera.core.application.port.outbound.ticks(40),
                com.astarworks.astera.core.application.port.outbound.ticks(40),
            ) { lanePadJoin.refreshBoard() }
        }

        // ── Wave E 5admin-ops (ADR-0122) — admin commands ───────────────────
        // `/astera admin stage <list|select>` for stage catalog visibility +
        // `/astera admin validate [category]` for cross-content reference integrity.
        // Validate is skipped when Combat isn't loaded (softdepend): without
        // ISkillRegistry the item→skill check has no skill table to consult.
        commands.register(
            "admin",
            com.astarworks.astera.match.command.admin.AdminStageCommandSpec(
                stageRegistry = stages,
            ).build(),
        )
        val skillRegistry = services.get<com.astarworks.astera.combat.application.port.outbound.ISkillRegistry>()
        if (skillRegistry != null) {
            val itemRegistry =
                services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IItemRegistry>()
            val validateUseCase =
                com.astarworks.astera.match.application.usecase.validate.ValidateContentReferencesUseCase(
                    items = itemRegistry,
                    skills = skillRegistry,
                )
            // Broken / unsupported content fails loudly at boot instead of
            // silently no-op'ing in game.
            validateUseCase.validate().issues.forEach { logger.severe("Content validation: $it") }
            commands.register(
                "admin",
                com.astarworks.astera.match.command.admin.AdminValidateCommandSpec(
                    validate = validateUseCase,
                ).build(),
            )
            logger.info("Wave E 5admin-ops: /astera admin stage + validate mounted.")
        } else {
            logger.info(
                "Wave E 5admin-ops: /astera admin stage mounted. " +
                    "/astera admin validate skipped (Astera-Combat not loaded; ISkillRegistry unavailable).",
            )
        }

        // ── F1-D (ADR-0183) — `/astera admin panel` match control section ─────────
        val adminWaivers = services.getOrThrow<com.astarworks.astera.match.application.admin.MatchMinPlayerWaivers>()
        val adminMatchPanel = com.astarworks.astera.match.application.admin.AdminMatchPanel(
            guard = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.AdminPanelGuard>(),
            registry = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry>(),
            guiAdapter = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiAdapter>(),
            matches = matches,
            matchmaker = matchmaker,
            matchFound = adminFormer,
            settings = { env.queueSettings.current() },
            nowTick = scheduler::currentTick,
            control = com.astarworks.astera.match.application.admin.AdminMatchControlUseCase(facade, matches, adminWaivers),
            forceStart = com.astarworks.astera.match.application.admin.ForceStartLaneUseCase(
                matchmaker = matchmaker,
                matchFound = adminFormer ?: com.astarworks.astera.match.application.admin.NoLanesFormer,
                matches = matches,
                lifecycle = facade,
                waivers = adminWaivers,
                settings = { env.queueSettings.current() },
            ),
            validate = skillRegistry?.let {
                com.astarworks.astera.match.application.usecase.validate.ValidateContentReferencesUseCase(
                    items = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IItemRegistry>(),
                    skills = it,
                )
            },
            hostsMatches = role.hostsMatches,
            gameServer = crossServer.gameServer.value,
        )
        registrations += adminMatchPanel.install(
            services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>(),
        )
    }
}
