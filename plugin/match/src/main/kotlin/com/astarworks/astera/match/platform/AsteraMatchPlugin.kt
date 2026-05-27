package com.astarworks.astera.match.platform

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerMovementGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerVisionGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.match.application.contents.AnomalyContents
import com.astarworks.astera.match.application.contents.GameModeContents
import com.astarworks.astera.match.application.gamemode.NormaGameMode
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationGameMode
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationMatchTracker
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.application.port.outbound.IGameModeScheduler
import com.astarworks.astera.match.application.service.DefaultGameModeScheduler
import com.astarworks.astera.match.application.service.InMemoryGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.gamemode.GameModeContext as FrameworkGameModeContext
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.application.eventbus.listeners.AuditLogListener
import com.astarworks.astera.match.application.eventbus.listeners.SaveInventoryOnMatchEndedListener
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
import com.astarworks.astera.match.application.contents.GeneratorContents
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.port.outbound.IAnomalyRegistry
import com.astarworks.astera.match.application.port.outbound.IAnomalyScheduleRepository
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.application.port.outbound.IGameModeRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.AnomalyScheduler
import com.astarworks.astera.match.application.service.BarrierStatusScheduler
import com.astarworks.astera.match.application.service.BlockChangeJournal
import com.astarworks.astera.match.application.service.BlockRecoveryScheduler
import com.astarworks.astera.match.application.service.InMemoryAnomalyScheduleRepository
import com.astarworks.astera.match.application.service.InMemoryBarrierGateway
import com.astarworks.astera.match.application.service.InMemoryGameModeRegistry
import com.astarworks.astera.match.application.service.InMemoryGeneratorRepository
import com.astarworks.astera.match.application.service.DefaultSpawnSelector
import com.astarworks.astera.match.application.service.ISpawnSelector
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.InMemoryMatchWorldRepository
import com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase
import com.astarworks.astera.match.application.service.StarGeneratorScheduler
import com.astarworks.astera.match.application.usecase.anomaly.ResolveAnomalyEffectUseCase
import com.astarworks.astera.match.application.usecase.anomaly.TriggerAnomalyUseCase
import com.astarworks.astera.match.application.usecase.block.BlockStateAutoRecoveryUseCase
import com.astarworks.astera.match.application.usecase.generator.AdvanceGeneratorChargeUseCase
import com.astarworks.astera.match.application.usecase.generator.SpawnResourceUseCase
import com.astarworks.astera.match.application.usecase.match.RestoreBlocksOnMatchEndedUseCase
import com.astarworks.astera.match.application.usecase.match.SuddenDeathAcceleratorUseCase
import com.astarworks.astera.match.application.usecase.world.CreateMatchWorldUseCase
import com.astarworks.astera.match.application.usecase.world.DestroyMatchWorldUseCase
import com.astarworks.astera.match.application.usecase.mode.BattleLoyalListener
import com.astarworks.astera.match.application.usecase.mode.BreakCoreListener
import com.astarworks.astera.match.application.usecase.mode.CaptureWoolListener
import com.astarworks.astera.match.application.usecase.mode.CarryMiniTNTListener
import com.astarworks.astera.match.application.usecase.mode.CarryTNTListener
import com.astarworks.astera.match.application.usecase.mode.StealJewelryListener
import com.astarworks.astera.match.domain.mode.BattleLoyal
import com.astarworks.astera.match.domain.mode.BreakCore
import com.astarworks.astera.match.domain.mode.CaptureWool
import com.astarworks.astera.match.domain.mode.CarryMiniTNT
import com.astarworks.astera.match.domain.mode.CarryTNT
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.domain.mode.StealJewelry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper
import com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener
import com.astarworks.astera.core.domain.lifecycle.ShutdownPhase
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.service.hud.LobbyWaitingRoomHud
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundUseCase
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.EntryGameError
import com.astarworks.astera.match.application.usecase.match.EntryGameUseCase
import com.astarworks.astera.match.application.usecase.match.PhaseTimerUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator
import com.astarworks.astera.match.application.usecase.spawn.MatchSpawnUseCase
import com.astarworks.astera.match.application.usecase.spawn.SpawnKind
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.stage.StageId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Astera-Match plugin entrypoint (ADR-0022 Phase 4 / Stage 3-改).
 *
 * Resolves shared services from Astera-Core, constructs the four lifecycle use
 * cases, exposes them via [IMatchLifecycle] on the [ServiceRegistry], and
 * registers the `/astera-match` command tree.
 *
 * The Persistence plugin (Stage 4-改) registers Postgres-backed impls at
 * higher priority and `services.getOrThrow<IMatchRepository>()` then
 * automatically returns the Postgres version without any wiring change here.
 */
public class AsteraMatchPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()
    private val subscriptions = mutableListOf<IDomainEventBus.Subscription>()
    private val scheduledTasks = mutableListOf<IScheduler.TaskHandle>()
    private var replayRecorder: ReplayCaptureEngine? = null

    /**
     * Wave 5match-hud (ADR-0118 §5) — owns the match-timer poll scheduler;
     * [onDisable] cancels via [HudUpdateOnMatchTickListener.stop].
     */
    private var matchTickHudHandle:
        com.astarworks.astera.match.application.eventbus.listeners.hud.HudUpdateOnMatchTickListener? = null

    /**
     * Wave 5match-hud (ADR-0118 §7) — owns per-player respawn countdown
     * schedulers; [onDisable] cancels via [HudUpdateOnRespawnScheduledListener.stop].
     */
    private var respawnHudHandle:
        com.astarworks.astera.match.application.eventbus.listeners.hud.HudUpdateOnRespawnScheduledListener? = null

    override fun onEnable() {
        logger.info("Astera-Match enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Match.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        // 1. Build match-owned repositories -------------------------------------
        // Wave 3 (R2): pick up Postgres impls (priority=100) if Astera-Persistence
        // registered them via loadbefore; otherwise create in-memory at priority=0.
        val matches: IMatchRepository =
            services.get<IMatchRepository>()
                ?: InMemoryMatchRepository().also {
                    registrations += services.register<IMatchRepository>(it, priority = 0)
                }

        // Wave _infra-2b (ADR-0065): StageContents replaces the legacy
        // InMemoryStageRepository + StageLoaderService pair. Always create the
        // YAML-backed contents (it participates in the /astera reload dispatcher),
        // register at priority 0; a Postgres overlay at priority 100 (if present)
        // still wins for runtime IStageRegistry lookups, but operators can refresh
        // YAML state for development via /astera reload stages.
        val stagesContentDir = core.contentDir.resolve("stages")
        val stageContents = StageContents()
        val stageReport = stageContents.bootstrap(stagesContentDir)
        logger.info("Loaded ${stageReport.loaded} stage(s) from $stagesContentDir (failed: ${stageReport.failed})")
        registrations += services.register<IStageRegistry>(stageContents, priority = 0)
        val stages: IStageRegistry = services.getOrThrow<IStageRegistry>()

        // Wave _infra-2b (ADR-0065): anomaly + generator template contents.
        // Both register on the /astera reload dispatcher; the runtime
        // IAnomalyScheduleRepository / IGeneratorRepository (per-match instances)
        // are wired separately further down (Wave 5c).
        val anomalyContentDir = core.contentDir.resolve("anomaly")
        val anomalyContents = AnomalyContents()
        val anomalyReport = anomalyContents.bootstrap(anomalyContentDir)
        logger.info("Loaded ${anomalyReport.loaded} anomaly schedule(s) from $anomalyContentDir (failed: ${anomalyReport.failed})")
        registrations += services.register<IAnomalyRegistry>(anomalyContents, priority = 0)

        val generatorContentDir = core.contentDir.resolve("generators")
        val generatorContents = GeneratorContents()
        val generatorReport = generatorContents.bootstrap(generatorContentDir)
        logger.info("Loaded ${generatorReport.loaded} generator template(s) from $generatorContentDir (failed: ${generatorReport.failed})")
        registrations += services.register<IGeneratorRegistry>(generatorContents, priority = 0)

        // 2. Resolve shared core services ---------------------------------------
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val scheduler = services.getOrThrow<IScheduler>()
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val commandHandler = services.getOrThrow<ICommandHandler>()
        val permService = services.getOrThrow<IPermissionService>()
        val reloadRegistry = services.getOrThrow<IReloadableContentsRegistry>()

        // Wave _infra-2b: register Match's 3 content types on the dispatcher.
        // /astera reload {stages, anomaly, generators} now route through Core.
        reloadRegistry.register(stageContents, stagesContentDir)
        reloadRegistry.register(anomalyContents, anomalyContentDir)
        reloadRegistry.register(generatorContents, generatorContentDir)

        @Suppress("UnusedVariable")
        val broadcaster = services.getOrThrow<IBroadcaster>()

        @Suppress("UnusedVariable")
        val msgRenderer = services.getOrThrow<IMessageRenderer>()

        // Wave 5game-complete (ADR-0121 §F + §E): mode registry mirrors
        // MatchStarted.modeKey into a lookup table that SwitchPhaseUseCase
        // (min-player guard) and DefaultPvpDeathPolicyResolver consume.
        // Registered at priority 0; an overlay impl may replace it later.
        val matchModeRegistry: com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry =
            services.get<com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry>()
                ?: com.astarworks.astera.match.application.service.InMemoryMatchModeRegistry().also {
                    registrations += services.register<com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry>(
                        it, priority = 0,
                    )
                }

        // 3. Construct use cases -----------------------------------------------
        val victoryEvaluator = VictoryConditionEvaluator()
        val startMatch = StartMatchUseCase(stages, matches, eventBus, scheduler)
        val endMatch = EndMatchUseCase(matches, eventBus, scheduler, victoryEvaluator)
        val entryGame = EntryGameUseCase(matches, eventBus)
        val switchPhase = SwitchPhaseUseCase(matches, eventBus, scheduler, matchModeRegistry)

        // Wave 4.5 (ADR-0027): phase auto-timer + match spawn
        val phaseTimer = PhaseTimerUseCase(matches, scheduler, switchPhase)
        scheduler.runRepeatingTicks(initialDelayTicks = 20L, periodTicks = 20L) {
            phaseTimer.tickAll()
        }

        // IWorldGateway is registered by Astera-Core (Wave 5 lands the Paper impl,
        // until then the binding might not be present in production; gate on null).
        val worldGateway = services.get<IWorldGateway>()
        if (worldGateway != null) {
            val matchSpawn = MatchSpawnUseCase(
                matchRepository = matches,
                stageRegistry = stages,
                worldGateway = worldGateway,
                eventBus = eventBus,
            )
            eventBus.subscribe(MatchStarted::class) { event ->
                val match = matches.findById(event.matchId) ?: return@subscribe
                match.teams.forEachIndexed { idx, team ->
                    val kind = if (idx == 0) SpawnKind.ATTACKER else SpawnKind.DEFENDER
                    team.members.forEach { playerId ->
                        matchSpawn.spawnPlayer(match.id, playerId, kind)
                    }
                }
            }
            logger.info("MatchSpawnUseCase wired (IWorldGateway present).")
        } else {
            logger.info("IWorldGateway not registered; spawn-on-MatchStarted skipped (lands in Wave 5).")
        }

        // 4. Expose IMatchLifecycle via ServiceRegistry -------------------------
        val facade = object : IMatchLifecycle {
            override fun start(stageId: StageId, modeKey: String?) =
                startMatch.execute(stageId, modeKey = modeKey)
            override fun end(matchId: MatchId, reason: EndReason) = endMatch.execute(matchId, reason)
            override fun entry(matchId: MatchId, playerId: PlayerId, teamId: TeamId?) =
                entryGame.execute(matchId, playerId, teamId)
            override fun switchPhase(matchId: MatchId, trigger: MatchStateMachine.TransitionTrigger) =
                switchPhase.execute(matchId, trigger)
        }
        registrations += services.register<IMatchLifecycle>(facade)

        // 4.5. Wave 5c — Game-mode registry + Star Generator + Anomaly + Sudden Death
        // -----------------------------------------------------------------
        // Repositories registered at priority 0 so a future Persistence override
        // (Wave 5e+) at priority 100 transparently replaces them. The schedulers
        // tick continuously and visit only the currently active matches.

        val gameModes: IGameModeRegistry =
            services.get<IGameModeRegistry>()
                ?: InMemoryGameModeRegistry().also {
                    registrations += services.register<IGameModeRegistry>(it, priority = 0)
                }
        registerDefaultGameModes(gameModes)
        logger.info("Game mode registry populated with ${gameModes.all().size} mode(s).")

        val generators: IGeneratorRepository =
            services.get<IGeneratorRepository>()
                ?: InMemoryGeneratorRepository().also {
                    registrations += services.register<IGeneratorRepository>(it, priority = 0)
                }

        val anomalySchedules: IAnomalyScheduleRepository =
            services.get<IAnomalyScheduleRepository>()
                ?: InMemoryAnomalyScheduleRepository().also {
                    registrations += services.register<IAnomalyScheduleRepository>(it, priority = 0)
                }

        // Star Generator pipeline (ADR-0032 + ADR-0058 §5 — per-instance modifier-aware)
        val spawnResource = SpawnResourceUseCase(eventBus, scheduler)
        val advanceCharge = AdvanceGeneratorChargeUseCase(generators, eventBus, scheduler, spawnResource)
        val starGenScheduler = StarGeneratorScheduler(
            generators = generators,
            advance = advanceCharge,
            spawn = spawnResource,
            scheduler = scheduler,
            eventBus = eventBus,
            matches = matches,
        )
        scheduledTasks += starGenScheduler.start()

        // Anomaly pipeline (ADR-0032 weighted-random tick driver + ADR-0072
        // pre-Paper-sweep gateway commitments). The new gateway ports are
        // resolved opportunistically via `services.get*()`; null branches
        // make the use case a clean no-op for variants whose impls are
        // deferred to Wave 6a/6b (Konsist-pinned allow-list).
        val triggerAnomaly = TriggerAnomalyUseCase(eventBus, scheduler)
        val resolveAnomaly = ResolveAnomalyEffectUseCase(
            generators = generators,
            statusEffectContainer = services.get(),
            matchTimerService = services.get(),
            playerMovementGateway = services.get(),
            playerVisionGateway = services.get(),
            playerAttributeStore = services.get(),
            matchPlayerListProvider = { mid ->
                matches.findById(mid)?.teams?.flatMap { it.members } ?: emptyList()
            },
        )
        val anomalyScheduler = AnomalyScheduler(
            repo = anomalySchedules,
            trigger = triggerAnomaly,
            resolve = resolveAnomaly,
            scheduler = scheduler,
            matches = matches,
        )
        scheduledTasks += anomalyScheduler.start()

        // Sudden Death accelerator — observes phase transitions and overlays a
        // MULTIPLY 0.5 modifier on every generator's PERIOD_MULTIPLIER while
        // SUDDEN_DEATH is active, removing it on exit.
        val suddenDeath = SuddenDeathAcceleratorUseCase(eventBus, generators)
        subscriptions += suddenDeath.subscribe()

        // Per-mode listeners are constructed lazily per match (Wave 6 stage YAML
        // wiring). At plugin load only BreakCore subscribes globally because its
        // BlockBroken trigger is mode-agnostic at the event layer — a server
        // running BreakCore matches will see the listener react to any matching
        // core block break.
        val breakCoreDefault = BreakCore(id = GameModeId("break-core"))
        val breakCoreListener = BreakCoreListener(
            eventBus = eventBus,
            matches = matches,
            lifecycle = facade,
            mode = breakCoreDefault,
        )
        subscriptions += breakCoreListener.subscribe()

        logger.info(
            "Wave 5c wiring complete: StarGeneratorScheduler + AnomalyScheduler running, " +
                "SuddenDeathAccelerator subscribed, BreakCoreListener subscribed.",
        )

        // 4.5.bis Wave 5gamemode (ADR-0106) — IGameMode framework foundation.
        // ---------------------------------------------------------------
        // The Wave 5c global pipeline (StarGenerator + Anomaly + SuddenDeath)
        // remains unchanged. This block adds the framework registry +
        // scheduler + NormaGameMode façade so Phase 3 (Siege, Deathmatch,
        // TDM, CTF) can be added as a YAML drop + new IGameMode impl with
        // zero changes here.
        val gameModeFrameworkRegistry: IGameModeFrameworkRegistry =
            services.get<IGameModeFrameworkRegistry>()
                ?: InMemoryGameModeFrameworkRegistry().also {
                    registrations += services.register<IGameModeFrameworkRegistry>(it, priority = 0)
                }

        val gameModeScheduler: IGameModeScheduler =
            services.get<IGameModeScheduler>()
                ?: DefaultGameModeScheduler(scheduler).also {
                    registrations += services.register<IGameModeScheduler>(it, priority = 0)
                }

        val gameModeContentDir = core.contentDir.resolve("game-modes")
        val gameModeContents = GameModeContents()
        val gameModeReport = gameModeContents.bootstrap(gameModeContentDir)
        logger.info(
            "Loaded ${gameModeReport.loaded} game-mode spec(s) from $gameModeContentDir " +
                "(failed: ${gameModeReport.failed})",
        )
        // Reflective impl resolution (Phase 1 PoC; Koin replaces this in
        // Phase 3 per ADR-0106). Each spec whose impl_class resolves to a
        // concrete IGameMode singleton becomes available via the registry.
        val registered = gameModeContents.registerInto(gameModeFrameworkRegistry) { msg: String ->
            logger.warning(msg)
        }
        // Fallback: if YAML bootstrap missed Norma (file deleted, bad YAML,
        // etc.) register it programmatically so Phase 1 PoC still ships a
        // working "norma" mode. The eventBus binding is wired in here so
        // lifecycle hooks publish framework events.
        if (gameModeFrameworkRegistry.get(NormaGameMode.ID) == null) {
            gameModeFrameworkRegistry.register(NormaGameMode(eventBus = eventBus))
            logger.info("game-modes: NormaGameMode programmatically registered (YAML fallback).")
        } else {
            // Replace the reflection-built (eventBus-less) instance with the
            // bus-wired one so framework events actually publish in prod.
            gameModeFrameworkRegistry.register(NormaGameMode(eventBus = eventBus))
        }

        // Wave 5annihilation (ADR-0108) — Astera Annihilation mode foundation.
        // AnnihilationGameMode owns its own AnnihilationMatchTracker; unlike
        // NormaGameMode (a façade over the Wave 5c global pipeline), the
        // tracker is the only writer of Annihilation match state and the
        // mode's onTick drives phase advances + sudden-death DoT.
        //
        // Replace the reflection-built (tracker-less + bus-less) instance with
        // a fully-wired one so framework events and tracker mutations actually
        // happen in production. Mirrors the Norma "replace YAML-resolved with
        // bus-wired" pattern above.
        val annihilationTracker = AnnihilationMatchTracker(eventBus)
        gameModeFrameworkRegistry.register(
            AnnihilationGameMode(
                tracker = annihilationTracker,
                eventBus = eventBus,
            ),
        )
        logger.info(
            "Wave 5annihilation wiring: AnnihilationGameMode + AnnihilationMatchTracker " +
                "registered into IGameModeFrameworkRegistry.",
        )

        reloadRegistry.register(gameModeContents, gameModeContentDir)
        logger.info(
            "Wave 5gamemode wiring complete: IGameModeFrameworkRegistry populated with " +
                "${gameModeFrameworkRegistry.all().size} mode(s) (YAML-resolved: " +
                "${registered.size}). DefaultGameModeScheduler registered.",
        )

        // Bridge: when a MatchStarted fires, resolve the match's mode via
        // [MatchStarted.modeKey] (Wave 5wireup §G16) and dispatch through
        // the framework hooks. `modeKey == null` keeps the legacy Norma
        // path — every existing caller (admin `/astera-match start`,
        // MatchFoundUseCase) defaults to Norma until they opt in.
        //
        // For Annihilation, the wiring pre-seeds `scoringState["annihilation.teams"]`
        // with the Match's team ids so AnnihilationGameMode.inferTeams (which
        // reads exactly that key) seeds the tracker with non-empty pillar
        // teams. Without this seed, AnnihilationMatchTracker.register()
        // creates an empty pillar set and the match cannot win-condition.
        subscriptions += eventBus.subscribe(MatchStarted::class) { event ->
            // Wave 5game-complete (ADR-0121 §F+§E): mirror modeKey for
            // SwitchPhase's min-player guard + PvP death-policy resolver.
            matchModeRegistry.register(event.matchId, event.modeKey)

            val modeId = resolveModeId(event.modeKey)
            val mode = gameModeFrameworkRegistry.get(modeId) ?: return@subscribe
            val match = matches.findById(event.matchId)
            val participants = match?.teams?.flatMap { it.members } ?: emptyList()
            val teamIds = match?.teams?.map { it.id }?.toSet() ?: emptySet()

            var ctx = FrameworkGameModeContext(
                matchId = event.matchId,
                modeId = mode.id(),
                participants = participants,
            )
            // Annihilation reads team ids from this key — seed it before
            // onMatchStarted fires so the tracker pillars are non-empty.
            if (modeId == AnnihilationGameMode.ID && teamIds.isNotEmpty()) {
                ctx = ctx.withState(AnnihilationGameMode.TEAMS_STATE_KEY, teamIds)
            }

            mode.onMatchStarted(ctx)
            gameModeScheduler.register(event.matchId, mode, ctx)
        }
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.PhaseChanged::class,
        ) { event ->
            // PhaseChanged carries no modeKey — fall back to Norma (Phase 1
            // PoC default; Wave 6 lands a per-match mode lookup on the event).
            val mode = gameModeFrameworkRegistry.get(NormaGameMode.ID) ?: return@subscribe
            val ctx = FrameworkGameModeContext(matchId = event.matchId, modeId = mode.id())
            val old = IGameModePhase.fromCore(event.from)
            val next = IGameModePhase.fromCore(event.to)
            mode.onPhaseChanged(ctx, old, next)
        }
        subscriptions += eventBus.subscribe(MatchEnded::class) { event ->
            // Wave 5game-complete (ADR-0121): forget per-match modeKey;
            // any post-end lookup falls back to KeepInventory / default.
            matchModeRegistry.unregister(event.matchId)
            // MatchEnded carries no modeKey. Dispatch through every registered
            // mode so each mode gets its onMatchEnded hook (cheap because
            // bus-less variants no-op). Wave 5wireup safer than hard-coding
            // Norma — Annihilation would silently skip cleanup otherwise.
            for (mode in gameModeFrameworkRegistry.all()) {
                val ctx = FrameworkGameModeContext(matchId = event.matchId, modeId = mode.id())
                mode.onMatchEnded(ctx)
            }
            gameModeScheduler.unregister(event.matchId)
        }

        // Wave 5a — friendly-fire team lookup adapter (Combat plugin resolves
        // IMatchTeamLookup via the shared registry to perform per-match team
        // checks without depending on Match directly).
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup>(
            com.astarworks.astera.match.application.service.MatchRepositoryTeamLookup(matches),
        )

        // 4.6. Wave 5d — World geometry (Block tracking + Barrier + MatchWorld
        // + Restoration). Schedulers tick continuously and visit only active
        // matches. Use cases expose `subscribe(bus)` and are wired here so
        // onDisable can cancel cleanly.
        // -----------------------------------------------------------------
        val blockStateGateway = services.getOrThrow<IBlockStateGateway>()
        @Suppress("UnusedVariable") // reserved for Wave 6 PaperSchematicLoader integration
        val schematicLoader = services.getOrThrow<ISchematicLoader>()

        val barrierGateway: IBarrierGateway =
            services.get<IBarrierGateway>()
                ?: InMemoryBarrierGateway().also {
                    registrations += services.register<IBarrierGateway>(it, priority = 0)
                }
        val matchWorlds: IMatchWorldRepository =
            services.get<IMatchWorldRepository>()
                ?: InMemoryMatchWorldRepository().also {
                    registrations += services.register<IMatchWorldRepository>(it, priority = 0)
                }

        // Block pseudo-recovery scheduler — re-uses the BlockStateGateway to
        // regenerate +5 HP every 10s for blocks past their cool-off window.
        val blockRecovery = BlockStateAutoRecoveryUseCase(blockStateGateway)
        val blockRecoveryScheduler = BlockRecoveryScheduler(
            matchWorlds = matchWorlds,
            recovery = blockRecovery,
            scheduler = scheduler,
        )
        scheduledTasks += blockRecoveryScheduler.start()

        // Barrier scheduler — heartbeat hook for future per-tick behavior
        // (Wave 5d ships an active no-op; behavior lands in Wave 6+).
        val barrierStatusScheduler = BarrierStatusScheduler(
            gateway = barrierGateway,
            scheduler = scheduler,
        )
        scheduledTasks += barrierStatusScheduler.start()

        // BlockChangeJournal subscribes to BlockPlaced/BlockBroken so the
        // RestoreBlocksOnMatchEndedUseCase can replay reverts on MatchEnded.
        val blockJournal = BlockChangeJournal()
        subscriptions += blockJournal.subscribe(eventBus)

        val restoreOnEnd = RestoreBlocksOnMatchEndedUseCase(
            gateway = blockStateGateway,
            journal = blockJournal,
            matchWorlds = matchWorlds,
        )
        subscriptions += restoreOnEnd.subscribe(eventBus)

        // Per-match world lifecycle — Create on MatchStarted, Destroy on MatchEnded.
        // Wave 5d ships in-memory only; Wave 6 wires PaperWorldGateway-backed
        // world creation. Soft-fail on schematic miss (logs + saves MatchWorld
        // record so block tracking + barrier placement still have a target).
        val createMatchWorld = CreateMatchWorldUseCase(
            schematicLoader = schematicLoader,
            matchWorlds = matchWorlds,
        )
        subscriptions += createMatchWorld.subscribe(eventBus)

        val destroyMatchWorld = DestroyMatchWorldUseCase(matchWorlds = matchWorlds)
        subscriptions += destroyMatchWorld.subscribe(eventBus)

        logger.info(
            "Wave 5d wiring complete: BlockRecoveryScheduler + BarrierStatusScheduler " +
                "running; BlockChangeJournal + RestoreBlocksOnMatchEnded + Create/" +
                "DestroyMatchWorld listeners subscribed.",
        )

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
                    removeFromMatch, eventBus, scheduler,
                )
            val resumeMatch =
                com.astarworks.astera.match.application.usecase.match.ResumeMatchUseCase(
                    sessionRepo, matches, worldGateway, eventBus, scheduler,
                )
            val disconnectListener =
                com.astarworks.astera.match.application.eventbus.listeners.DisconnectDuringMatchListener(
                    sessionRepo, matches, removeFromMatch, applyForfeit, eventBus, scheduler,
                )
            val reconnectListener =
                com.astarworks.astera.match.application.eventbus.listeners.ReconnectDuringMatchListener(
                    sessionRepo, resumeMatch, worldGateway,
                )
            subscriptions += eventBus.subscribe(
                com.astarworks.astera.core.domain.event.PlayerQuitServer::class,
            ) { disconnectListener.handle(it) }
            subscriptions += eventBus.subscribe(PlayerJoinedServer::class) {
                reconnectListener.handle(it)
            }
        }

        val pinService = InMemoryPlayerPinService(eventBus)
        registrations += services.register<IPlayerPinService>(pinService, priority = 0)
        @Suppress("UNUSED_VARIABLE") val placePin = PlacePinUseCase(pinService)
        @Suppress("UNUSED_VARIABLE") val removePin = RemovePinUseCase(pinService)

        // Audit log: AuditLogListener handles each event variant via a method per type.
        val auditRepo = services.getOrThrow<IAuditLogRepository>()
        val auditListener = AuditLogListener(auditRepo)
        subscriptions += eventBus.subscribe(ChatMessageSent::class) { auditListener.handleChatMessageSent(it) }
        subscriptions += eventBus.subscribe(MatchStarted::class) { auditListener.handleMatchStarted(it) }
        subscriptions += eventBus.subscribe(MatchEnded::class) { auditListener.handleMatchEnded(it) }

        // Inventory snapshot save on MatchEnded (per-participant placeholder; Wave 6b
        // replaces with a real Bukkit-inventory capture).
        val snapshotRepo = services.getOrThrow<IPlayerInventorySnapshotRepository>()
        val saveSnapshotUseCase = SaveInventorySnapshotUseCase(snapshotRepo)
        val saveInventoryOnMatchEnded = SaveInventoryOnMatchEndedListener(saveSnapshotUseCase, matches)
        subscriptions += eventBus.subscribe(MatchEnded::class) { saveInventoryOnMatchEnded.handle(it) }

        logger.info("Wave 5e wiring complete: Spectator + PlayerPin + AuditLog + SaveInventory listeners.")

        // ---- Wave 5v: Match recording capture (ADR-0088) --------------------
        // Repository: registered at priority 0 (in-memory) so an absent
        // Astera-Persistence plugin still produces well-formed RecordedReplay
        // objects (just lost on shutdown). Postgres impl overlays at
        // priority 100 from AsteraPersistencePlugin.
        val replayRepo: IMatchReplayRepository =
            services.get<IMatchReplayRepository>()
                ?: InMemoryMatchReplayRepository().also {
                    registrations += services.register<IMatchReplayRepository>(it, priority = 0)
                }

        // State provider: registered at priority 0 (Noop). Wave 6a's
        // PaperRecorderStateProvider overlays at priority 100; in absence,
        // key frames carry empty per-player maps (still well-formed).
        val recorderStateProvider: IRecorderStateProvider =
            services.get<IRecorderStateProvider>()
                ?: NoopRecorderStateProvider().also {
                    registrations += services.register<IRecorderStateProvider>(it, priority = 0)
                }

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
        replayRecorder = recorder

        logger.info("Wave 5v wiring complete: ReplayCaptureEngine registered as IMatchRecorder.")

        // 5. Register /astera-match command tree --------------------------------
        commandHandler.register(
            buildMatchCommandSpec(
                lifecycle = facade,
                matches = matches,
                playerGateway = playerGateway,
                permService = permService,
                recorder = recorder,
                replayRepo = replayRepo,
                exportRoot = core.dataFolder.toPath().resolve("replays"),
            ),
        )

        // ── Wave 5wireup (ADR-0116) ──────────────────────────────────────
        //
        // G9: register the queue command spec defined above but never wired.
        //     Includes instantiating + registering the matchmaker itself —
        //     Wave 5w defined `InMemoryMatchmaker` but no production wiring
        //     used it (tests only). Phase 1 PoC defaults: 2 teams, 1 player
        //     per team minimum (= 2 players total).
        // G4: subscribe RespawnOnPlayerKilledListener.
        // G5: subscribe TeleportToLobbyOnMatchEndedListener.
        val matchmaker: IMatchmaker =
            services.get<IMatchmaker>()
                ?: InMemoryMatchmaker(eventBus = eventBus, scheduler = scheduler).also {
                    registrations += services.register<IMatchmaker>(it, priority = 0)
                }
        commandHandler.register(
            buildQueueCommandSpec(
                matchmaker = matchmaker,
                playerGateway = playerGateway,
                permService = permService,
            ),
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
        val respawnSelectorPort: com.astarworks.astera.match.application.service.ISpawnSelector =
            services.get<com.astarworks.astera.match.application.service.ISpawnSelector>()
                ?: com.astarworks.astera.match.application.service.DefaultSpawnSelector().also {
                    registrations += services.register<com.astarworks.astera.match.application.service.ISpawnSelector>(it, priority = 0)
                }
        val respawnOnKilled = com.astarworks.astera.match.application.eventbus.listeners.respawn
            .RespawnOnPlayerKilledListener(
                matches = matches,
                stages = stages,
                spawnSelector = respawnSelectorPort,
                respawnUseCase = respawnUseCase,
            )
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.combat.domain.event.PlayerKilled::class,
        ) { event -> respawnOnKilled.handle(event) }

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

        logger.info(
            "Astera-Match enabled. Services: IMatchRepository, IStageRegistry, " +
                "IAnomalyRegistry, IGeneratorRegistry, IMatchLifecycle, IMatchTeamLookup, " +
                "IMatchModeRegistry, IPvpDeathPolicyResolver, " +
                "IMatchRecorder, IMatchReplayRepository, IRecorderStateProvider",
        )
    }

    override fun onDisable() {
        matchTickHudHandle?.stop()
        matchTickHudHandle = null
        respawnHudHandle?.stop()
        respawnHudHandle = null
        replayRecorder?.shutdown()
        replayRecorder = null
        scheduledTasks.forEach { it.cancel() }
        scheduledTasks.clear()
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Match disabled.")
    }

    /**
     * Populate [IGameModeRegistry] with the 6 Wave 5c modes (ADR-0032). Per-
     * match config (region maps, item ids) is left at the data-class defaults
     * — stage YAML overrides them when matches start in Wave 6.
     */
    /**
     * Resolve the `MatchStarted.modeKey` string into the framework
     * [com.astarworks.astera.match.domain.mode.GameModeId] used by
     * [gameModeFrameworkRegistry] (Wave 5wireup §G16).
     *
     * `null` / empty / unknown → fall back to [NormaGameMode.ID] so any
     * call site that ships without a mode key keeps the Phase 1 PoC Norma
     * behaviour. Recognised keys today: `"annihilation"`, `"norma"`. New
     * modes register their key here when they join the framework.
     */
    private fun resolveModeId(
        modeKey: String?,
    ): com.astarworks.astera.match.domain.mode.GameModeId = when (modeKey) {
        AnnihilationGameMode.ID.value -> AnnihilationGameMode.ID
        NormaGameMode.ID.value, null, "" -> NormaGameMode.ID
        else -> NormaGameMode.ID
    }

    private fun registerDefaultGameModes(registry: IGameModeRegistry) {
        registry.register(BattleLoyal(id = GameModeId("battle-loyal")))
        registry.register(BreakCore(id = GameModeId("break-core")))
        registry.register(
            CarryTNT(
                id = GameModeId("carry-tnt"),
                displayNameKey = MessageKey("astera.match.mode.carry-tnt.name"),
                descriptionKey = MessageKey("astera.match.mode.carry-tnt.description"),
                deliveryRegionIds = mapOf(
                    TeamId("red") to "blue_base",
                    TeamId("blue") to "red_base",
                ),
            ),
        )
        registry.register(
            CarryMiniTNT(
                id = GameModeId("carry-mini-tnt"),
                displayNameKey = MessageKey("astera.match.mode.carry-mini-tnt.name"),
                descriptionKey = MessageKey("astera.match.mode.carry-mini-tnt.description"),
                deliveryRegionIds = mapOf(
                    TeamId("red") to "blue_base",
                    TeamId("blue") to "red_base",
                ),
            ),
        )
        registry.register(
            CaptureWool(
                id = GameModeId("capture-wool"),
                displayNameKey = MessageKey("astera.match.mode.capture-wool.name"),
                descriptionKey = MessageKey("astera.match.mode.capture-wool.description"),
                controlRegionIds = listOf("wool_a", "wool_b", "wool_c"),
            ),
        )
        registry.register(
            StealJewelry(
                id = GameModeId("steal-jewelry"),
                displayNameKey = MessageKey("astera.match.mode.steal-jewelry.name"),
                descriptionKey = MessageKey("astera.match.mode.steal-jewelry.description"),
                depositRegionIds = mapOf(
                    TeamId("red") to "red_deposit",
                    TeamId("blue") to "blue_deposit",
                ),
            ),
        )
    }

    /**
     * Wave 5w (ADR-0089 δ): `/astera queue join <mode>` / `leave` / `status`.
     *
     * The mode argument accepts:
     *   - `norma` — `MatchmakingMode.Norma`
     *   - `custom:<game-mode-id>` — `MatchmakingMode.Custom`
     *   - `tournament:<id>` — `MatchmakingMode.Tournament`
     *
     * Wave 7b (ADR-0037) replaces this with a richer subcommand tree once
     * tournament brackets and rating-based modes land.
     */
    private fun buildQueueCommandSpec(
        matchmaker: IMatchmaker,
        playerGateway: IPlayerGateway,
        permService: IPermissionService,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-queue",
        description = MessageKey("astera.match.matchmaking.enqueue.success"),
        subcommands = listOf(
            CommandSpec.Literal(
                name = "join",
                description = MessageKey("astera.match.matchmaking.enqueue.success"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "mode",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val senderId = ctx.sender
                        ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                    val modeStr = ctx.required<String>("mode")
                    val mode = parseMatchmakingMode(modeStr)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.matchmaking.enqueue.invalid-mode"),
                            mapOf("mode" to modeStr),
                        )
                    when (val res = matchmaker.enqueue(senderId, mode, partyId = null)) {
                        is Result.Success -> {
                            playerGateway.sendMessage(senderId, "Joined queue: $modeStr")
                            CommandResult.Success
                        }
                        is Result.Failure -> {
                            val key = when (res.error) {
                                is com.astarworks.astera.match.domain.matchmaking.QueueError.AlreadyEnqueued ->
                                    MessageKey("astera.match.matchmaking.enqueue.already-enqueued")
                                is com.astarworks.astera.match.domain.matchmaking.QueueError.ModeClosed ->
                                    MessageKey("astera.match.matchmaking.enqueue.mode-closed")
                                else -> MessageKey("astera.match.matchmaking.queue-failure")
                            }
                            CommandResult.Failure(key)
                        }
                    }
                },
            ),
            CommandSpec.Literal(
                name = "leave",
                description = MessageKey("astera.match.matchmaking.dequeue.success"),
                executor = { ctx ->
                    val senderId = ctx.sender
                        ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                    when (val res = matchmaker.dequeue(senderId)) {
                        is Result.Success -> {
                            playerGateway.sendMessage(senderId, "Left the queue.")
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(
                            MessageKey("astera.match.matchmaking.dequeue.not-enqueued"),
                        )
                    }
                },
            ),
            CommandSpec.Literal(
                name = "status",
                description = MessageKey("astera.match.matchmaking.status.line"),
                executor = { ctx ->
                    val senderId = ctx.sender
                        ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                    val status = matchmaker.queueStatus(senderId)
                    if (status == null) {
                        CommandResult.Failure(MessageKey("astera.match.matchmaking.status.none"))
                    } else {
                        playerGateway.sendMessage(senderId, "In queue: ${status.mode}")
                        CommandResult.Success
                    }
                },
            ),
        ),
    ).requirePermission(
        permissionService = permService,
        permission = Permission("astera.match.queue"),
    )

    /** Parse `"norma"` / `"custom:break-core"` / `"tournament:cup-2026"` into [MatchmakingMode]. */
    private fun parseMatchmakingMode(raw: String): MatchmakingMode? {
        val lower = raw.lowercase()
        if (lower == "norma") return MatchmakingMode.Norma
        if (lower.startsWith("custom:")) {
            val gmId = raw.substringAfter(":").takeIf { it.isNotBlank() } ?: return null
            return runCatching { MatchmakingMode.Custom(GameModeId(gmId)) }.getOrNull()
        }
        if (lower.startsWith("tournament:")) {
            val tid = raw.substringAfter(":").takeIf { it.isNotBlank() } ?: return null
            return runCatching { MatchmakingMode.Tournament(tid) }.getOrNull()
        }
        return null
    }

    private fun buildMatchCommandSpec(
        lifecycle: IMatchLifecycle,
        matches: IMatchRepository,
        playerGateway: IPlayerGateway,
        permService: IPermissionService,
        recorder: IMatchRecorder,
        replayRepo: IMatchReplayRepository,
        exportRoot: Path,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-match",
        description = MessageKey("astera.match.command.root"),
        subcommands = listOf(
            // astera-match start <stageId>
            CommandSpec.Literal(
                name = "start",
                description = MessageKey("astera.match.command.start"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "stage-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val stageIdStr = ctx.required<String>("stage-id")
                    val stageId = runCatching { StageId(stageIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.start.invalid-stage"),
                            mapOf("stage" to stageIdStr),
                        )
                    when (val result = lifecycle.start(stageId)) {
                        is Result.Success -> {
                            val senderId = ctx.sender
                            if (senderId != null) {
                                playerGateway.sendMessage(senderId, "Match started: ${result.value.value}")
                            }
                            CommandResult.Success
                        }
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.start"),
            ),
            // astera-match end <matchId>
            CommandSpec.Literal(
                name = "end",
                description = MessageKey("astera.match.command.end"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.end.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = lifecycle.end(matchId, EndReason.AdminEnded)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.end"),
            ),
            // astera-match entry <matchId> <playerName>
            CommandSpec.Literal(
                name = "entry",
                description = MessageKey("astera.match.command.entry"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                    CommandSpec.Argument(
                        name = "player",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val playerName = ctx.required<String>("player")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.entry.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    val playerId = playerGateway.findByName(playerName)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.entry.player-not-found"),
                            mapOf("player" to playerName),
                        )
                    when (val result = lifecycle.entry(matchId, playerId, null)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ),
            // astera-match phase <matchId> <triggerName>
            CommandSpec.Literal(
                name = "phase",
                description = MessageKey("astera.match.command.phase"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                    CommandSpec.Argument(
                        name = "trigger",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val triggerName = ctx.required<String>("trigger")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.phase.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    val trigger = parseTrigger(triggerName)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.phase.invalid-trigger"),
                            mapOf("trigger" to triggerName),
                        )
                    when (val result = lifecycle.switchPhase(matchId, trigger)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.phase"),
            ),
            // astera-match list
            CommandSpec.Literal(
                name = "list",
                description = MessageKey("astera.match.command.list"),
                executor = { ctx ->
                    val active = matches.findActiveMatches()
                    if (active.isEmpty()) {
                        CommandResult.Failure(MessageKey("astera.match.command.list.none"))
                    } else {
                        val senderId = ctx.sender
                        active.forEach { m ->
                            val summary = "${m.id.value} [${m.phase}] stage=${m.stageId.value}"
                            if (senderId != null) {
                                playerGateway.sendMessage(senderId, summary)
                            }
                        }
                        CommandResult.Success
                    }
                },
            ),
            // astera-match abort <matchId> [reason]  (Wave 5complete, ADR-0117 §Axis 3)
            //   AdminEnded the match immediately. Reason is free-form for
            //   audit (defaults to "abort").
            CommandSpec.Literal(
                name = "abort",
                description = MessageKey("astera.match.command.abort"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.end.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = lifecycle.end(matchId, EndReason.AdminEnded)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.abort"),
            ),
            // astera-match cancel <matchId>  (Wave 5complete, ADR-0117 §Axis 3)
            //   End the match with a Cancelled reason so reward distribution +
            //   audit can branch on "this match never had a winner".
            CommandSpec.Literal(
                name = "cancel",
                description = MessageKey("astera.match.command.cancel"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.end.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = lifecycle.end(matchId, EndReason.Cancelled("admin-cancel"))) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> result.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.cancel"),
            ),
            // astera-match restart <matchId>  (Wave 5complete, ADR-0117 §Axis 3)
            //   End the current match + start a new one on the same stage.
            //   The two-step is best-effort: if start fails the players are
            //   already booted from the prior match (TeleportToLobbyOnMatchEnded
            //   handles teleport-out), so they land in lobby and can re-queue.
            CommandSpec.Literal(
                name = "restart",
                description = MessageKey("astera.match.command.restart"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.end.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    val match = matches.findById(matchId)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.error.match-not-found"),
                            mapOf("match" to matchIdStr),
                        )
                    val savedStage = match.stageId
                    val endOutcome = lifecycle.end(matchId, EndReason.AdminEnded)
                    if (endOutcome is Result.Failure) return@Literal endOutcome.error.toCommandFailure()
                    when (val startOutcome = lifecycle.start(savedStage)) {
                        is Result.Success -> CommandResult.Success
                        is Result.Failure -> startOutcome.error.toCommandFailure()
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.restart"),
            ),
            // astera-match replay <start|stop|export> <matchId> (Wave 5v / ADR-0088)
            buildReplayCommandSpec(recorder, replayRepo, permService, exportRoot, playerGateway),
        ),
    )

    /**
     * Wave 5v: `/astera-match replay <subcommand>` admin commands. Three
     * subcommands: `start <matchId>` begins capture under the default
     * `EventLogKeyFrame` format; `stop <matchId>` finalises and persists;
     * `export <matchId>` writes the most recent persisted replay for the
     * match to a `.replay.json` file under the plugin's data folder.
     *
     * Permissions:
     *  - `astera.match.admin.replay.start`
     *  - `astera.match.admin.replay.stop`
     *  - `astera.match.admin.replay.export`
     */
    private fun buildReplayCommandSpec(
        recorder: IMatchRecorder,
        replayRepo: IMatchReplayRepository,
        permService: IPermissionService,
        exportRoot: Path,
        playerGateway: IPlayerGateway,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "replay",
        description = MessageKey("astera.match.command.replay"),
        subcommands = listOf(
            CommandSpec.Literal(
                name = "start",
                description = MessageKey("astera.match.command.replay.start"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.replay.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    recorder.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
                    val senderId = ctx.sender
                    if (senderId != null) {
                        playerGateway.sendMessage(senderId, "Recording started for $matchIdStr")
                    }
                    CommandResult.Success
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.replay.start"),
            ),
            CommandSpec.Literal(
                name = "stop",
                description = MessageKey("astera.match.command.replay.stop"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.replay.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    when (val result = recorder.stopRecording(matchId)) {
                        is Result.Success -> {
                            val senderId = ctx.sender
                            if (senderId != null) {
                                playerGateway.sendMessage(
                                    senderId,
                                    "Recording stopped: events=${result.value.events.size} " +
                                        "keyFrames=${result.value.keyFrames.size} " +
                                        "sizeBytes=${result.value.sizeBytes}",
                                )
                            }
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(result.error.messageKey)
                    }
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.replay.stop"),
            ),
            CommandSpec.Literal(
                name = "export",
                description = MessageKey("astera.match.command.replay.export"),
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "match-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    ),
                ),
                executor = { ctx ->
                    val matchIdStr = ctx.required<String>("match-id")
                    val matchId = runCatching { MatchId(matchIdStr) }.getOrNull()
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.replay.invalid-id"),
                            mapOf("match" to matchIdStr),
                        )
                    val replay = replayRepo.findByMatchId(matchId)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.replay.not-found"),
                            mapOf("match" to matchIdStr),
                        )
                    val outPath = try {
                        exportReplayToFile(replay, exportRoot)
                    } catch (t: Throwable) {
                        logger.severe("Replay export failed for $matchIdStr: ${t.message}")
                        return@Literal CommandResult.Failure(
                            MessageKey("astera.match.command.replay.export-failed"),
                            mapOf("match" to matchIdStr, "reason" to (t.message ?: "unknown")),
                        )
                    }
                    val senderId = ctx.sender
                    if (senderId != null) {
                        playerGateway.sendMessage(senderId, "Replay exported to $outPath")
                    }
                    CommandResult.Success
                },
            ).requirePermission(
                permissionService = permService,
                permission = Permission("astera.match.admin.replay.export"),
            ),
        ),
    )

    /**
     * Serialise [replay] into the stable `MatchReplayJson` wire shape and
     * write it under [exportRoot] as `<matchId>.replay.json`. Returns the
     * absolute path written.
     */
    private fun exportReplayToFile(
        replay: com.astarworks.astera.core.domain.replay.RecordedReplay,
        exportRoot: Path,
    ): Path {
        Files.createDirectories(exportRoot)
        val outFile = exportRoot.resolve("${replay.matchId.value}.replay.json")
        val exportEnvelope = com.astarworks.astera.match.application.replay.json.ReplayExportEnvelope(
            id = replay.id.uuid.toString(),
            matchId = replay.matchId.value,
            startTick = replay.startTick,
            endTick = replay.endTick,
            sizeBytes = replay.sizeBytes,
            format = com.astarworks.astera.match.application.replay.json.ReplayFormatJson.fromDomain(replay.format),
            keyFrames = com.astarworks.astera.match.application.replay.json.KeyFramesJson.fromDomain(replay.keyFrames),
            events = com.astarworks.astera.match.application.replay.json.ReplayEventsJson.fromDomain(replay.events),
        )
        val json = Json {
            prettyPrint = false
            encodeDefaults = true
            classDiscriminator = "type"
        }
        val payload = json.encodeToString(exportEnvelope)
        Files.write(
            outFile,
            payload.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        return outFile.toAbsolutePath()
    }

    private fun parseTrigger(name: String): MatchStateMachine.TransitionTrigger? =
        when (name.lowercase()) {
            "countdowncomplete" -> MatchStateMachine.TransitionTrigger.CountdownComplete
            "scorethresholdreached" -> MatchStateMachine.TransitionTrigger.ScoreThresholdReached
            "suddendeathtimer" -> MatchStateMachine.TransitionTrigger.SuddenDeathTimer
            "cancel" -> MatchStateMachine.TransitionTrigger.Cancel
            "adminend" -> MatchStateMachine.TransitionTrigger.AdminEnd
            else -> null
        }
}

private fun StartMatchError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)

private fun EndMatchError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)

private fun EntryGameError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)

private fun SwitchPhaseError.toCommandFailure(): CommandResult.Failure =
    CommandResult.Failure(messageKey)
