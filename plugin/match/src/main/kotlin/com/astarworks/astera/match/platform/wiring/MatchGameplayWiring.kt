package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
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
import com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator
import com.astarworks.astera.core.domain.event.MatchStarted

/** Queue, rating, respawn, HUD, win-condition, lobby intent, admin commands (original Wave 5wireup .. 5admin-ops). */
internal object MatchGameplayWiring {

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
        val commandHandler = env.commandHandler
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
        commandHandler.register(
            MatchCommandSpecs.buildQueueCommandSpec(
                matchmaker = matchmaker,
                matches = matches,
                playerGateway = playerGateway,
                permService = permService,
                messages = env.msgRenderer,
                settings = { env.queueSettings.current() },
            ),
        )

        // ── WP8: queue → match. Scheduled tryFormMatch + auto start/entry ──
        val queueNotifier = com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueNotifier { id, key, ph ->
            playerGateway.sendMessage(id, env.msgRenderer.render(id, key, ph))
        }
        val matchFound = com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundUseCase(
            matchmaker = matchmaker,
            lifecycle = facade,
            matches = matches,
            notifier = queueNotifier,
            planFor = { mode ->
                val lane = env.queueSettings.current().laneFor(mode)
                    ?: com.astarworks.astera.match.domain.matchmaking.QueueSettings.DEFAULT.lanes.getValue("norma")
                com.astarworks.astera.match.application.usecase.matchmaking.MatchPlan(lane.stage, lane.modeKey)
            },
        )
        scheduledTasks += com.astarworks.astera.match.application.service.matchmaking.QueueMatchScheduler(
            matchmaker = matchmaker,
            matchFound = matchFound,
            notifier = queueNotifier,
            scheduler = scheduler,
            settings = { env.queueSettings.current() },
        ).start()
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

        val respawnUseCase = com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase(
            healthGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway>(),
            worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
            itemRegistry = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IItemRegistry>(),
            protectionGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway>(),
            eventBus = eventBus,
            scheduler = scheduler,
            statusEffectContainer = services.get<com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer>(),
        )
        val respawnSelectorPort: ISpawnSelector = services.getOrThrow<ISpawnSelector>()
        val eliminatedTeams = com.astarworks.astera.match.application.eventbus.listeners.respawn.EliminatedTeamSpectators(
            matches = matches,
            healthGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway>(),
            worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
            enterSpectator = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository>()?.let {
                com.astarworks.astera.match.application.usecase.spectator.EnterSpectatorUseCase(
                    sessionRepository = it,
                    worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
                    eventBus = eventBus,
                    scheduler = scheduler,
                )
            },
        )
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.TeamEliminated::class) {
            eliminatedTeams.onTeamEliminated(it)
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchEnded::class) {
            eliminatedTeams.onMatchEnded(it)
        }
        val respawnOnKilled = com.astarworks.astera.match.application.eventbus.listeners.respawn
            .RespawnOnPlayerKilledListener(
                matches = matches,
                stages = stages,
                spawnSelector = respawnSelectorPort,
                respawnUseCase = respawnUseCase,
                eliminatedTeams = eliminatedTeams,
            )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.combat.domain.event.PlayerKilled::class,
        ) { event -> respawnOnKilled.handle(event) }
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
        subscriptions += eventBus.subscribe(MatchEnded::class) { event ->
            teleportLobbyListener.handle(event)
        }

        logger.info(
            "Wave 5wireup wired: /astera-queue command registered; " +
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
        //   UpdateMatchSidebarListener           — pushes per-team scoreboard
        //                                          on MatchStarted + EntityPickedUpItem
        //   KillfeedOnPlayerKilledListener       — surfaces kill ticks via
        //                                          HUD (victim actionbar, killer
        //                                          subtitle, broadcast actionbar)
        //   NormaVictoryConditionEvaluatorListener — auto-fires
        //                                          ScoreThresholdReached when
        //                                          VictoryConditionEvaluator finds
        //                                          a unique winner; eliminates the
        //                                          admin-manual /astera-match phase
        //                                          step from the Norma win path.
        //
        // Sidebar + killfeed talk to the Core IHudGateway (Phase 1 in-memory
        // by default; Wave 6b PaperHudGateway overlays at priority 100).
        val hudGateway: com.astarworks.astera.core.application.port.outbound.IHudGateway =
            services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        val sidebarListener = com.astarworks.astera.match.application.eventbus.listeners.hud
            .UpdateMatchSidebarListener(matches = matches, hudGateway = hudGateway)
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.MatchStarted::class,
        ) { sidebarListener.onMatchStarted(it) }
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.EntityPickedUpItem::class,
        ) { sidebarListener.onItemPickedUp(it) }

        val killfeedListener = com.astarworks.astera.match.application.eventbus.listeners.killfeed
            .KillfeedOnPlayerKilledListener(matches = matches, hudGateway = hudGateway)
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.combat.domain.event.PlayerKilled::class,
        ) { killfeedListener.handle(it) }

        val normaVictoryListener = com.astarworks.astera.match.application.eventbus.listeners.victory
            .NormaVictoryConditionEvaluatorListener(
                matches = matches,
                switchPhase = com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase(
                    matches = matches,
                    eventBus = eventBus,
                    scheduler = scheduler,
                ),
            )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.EntityPickedUpItem::class,
        ) { normaVictoryListener.handle(it) }

        logger.info(
            "Wave 5complete wired: UpdateMatchSidebar + KillfeedOnPlayerKilled + " +
                "NormaVictoryConditionEvaluator subscribed; " +
                "/astera-match {abort,cancel,restart} sub-commands registered.",
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

        val matchEndSummaryListener =
            com.astarworks.astera.match.application.eventbus.listeners.match
                .MatchEndSummaryListener(
                    matches = matches,
                    hudGateway = hudGateway,
                    messages = msgRenderer,
                    playerGateway = playerGateway,
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
        val lobbyQueueIntentHandler =
            com.astarworks.astera.match.application.intent.LobbyQueueIntentHandler(
                matchmaker = matchmaker,
                parties = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPartyRepository>(),
                guiAdapter = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiAdapter>(),
                mcServer = services.getOrThrow<IMcServer>(),
                messages = services.getOrThrow<IMessageRenderer>(),
                playerGateway = playerGateway,
            )
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

        // ── Wave E 5admin-ops (ADR-0122) — admin commands ───────────────────
        // `/astera-stage <list|select>` for stage catalog visibility +
        // `/astera-validate [category]` for cross-content reference integrity.
        // Validate is skipped when Combat isn't loaded (softdepend): without
        // ISkillRegistry the item→skill check has no skill table to consult.
        commandHandler.register(
            com.astarworks.astera.match.command.admin.AdminStageCommandSpec(
                stageRegistry = stages,
                permissionService = permService,
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
            commandHandler.register(
                com.astarworks.astera.match.command.admin.AdminValidateCommandSpec(
                    validate = validateUseCase,
                    permissionService = permService,
                ).build(),
            )
            logger.info("Wave E 5admin-ops: /astera-stage + /astera-validate registered.")
        } else {
            logger.info(
                "Wave E 5admin-ops: /astera-stage registered. " +
                    "/astera-validate skipped (Astera-Combat not loaded; ISkillRegistry unavailable).",
            )
        }
    }
}
