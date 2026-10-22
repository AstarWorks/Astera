package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.match.application.contents.AnomalyContents
import com.astarworks.astera.match.application.contents.GameModeContents
import com.astarworks.astera.match.application.gamemode.BattleLoyalGameMode
import com.astarworks.astera.match.application.gamemode.BreakCoreGameMode
import com.astarworks.astera.match.application.gamemode.CaptureWoolGameMode
import com.astarworks.astera.match.application.gamemode.CarryMiniTntGameMode
import com.astarworks.astera.match.application.gamemode.CarryTntGameMode
import com.astarworks.astera.match.application.gamemode.NormaGameMode
import com.astarworks.astera.match.application.gamemode.StealJewelryGameMode
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationGameMode
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationMatchTracker
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.application.port.outbound.IGameModeScheduler
import com.astarworks.astera.match.application.service.DefaultGameModeScheduler
import com.astarworks.astera.match.application.service.InMemoryGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.gamemode.GameModeContext as FrameworkGameModeContext
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.application.contents.GeneratorContents
import com.astarworks.astera.match.application.contents.QueueSettingsContents
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.port.outbound.IAnomalyRegistry
import com.astarworks.astera.match.application.port.outbound.IAnomalyScheduleRepository
import com.astarworks.astera.match.application.port.outbound.IGameModeRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.service.AnomalyScheduler
import com.astarworks.astera.match.application.service.InMemoryAnomalyScheduleRepository
import com.astarworks.astera.match.application.service.InMemoryGameModeRegistry
import com.astarworks.astera.match.application.service.InMemoryGeneratorRepository
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.StarGeneratorScheduler
import com.astarworks.astera.match.application.usecase.anomaly.ResolveAnomalyEffectUseCase
import com.astarworks.astera.match.application.usecase.anomaly.TriggerAnomalyUseCase
import com.astarworks.astera.match.application.usecase.generator.AdvanceGeneratorChargeUseCase
import com.astarworks.astera.match.application.usecase.generator.SpawnResourceUseCase
import com.astarworks.astera.match.application.usecase.match.SuddenDeathAcceleratorUseCase
import com.astarworks.astera.match.application.usecase.mode.BreakCoreListener
import com.astarworks.astera.match.domain.mode.BattleLoyal
import com.astarworks.astera.match.domain.mode.BreakCore
import com.astarworks.astera.match.domain.mode.CaptureWool
import com.astarworks.astera.match.domain.mode.CarryMiniTNT
import com.astarworks.astera.match.domain.mode.CarryTNT
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.domain.mode.StealJewelry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.match.application.usecase.matchmaking.MatchFoundUseCase
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.EntryGameUseCase
import com.astarworks.astera.match.application.usecase.match.PhaseTimerUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator
import com.astarworks.astera.match.application.usecase.spawn.MatchSpawnUseCase
import com.astarworks.astera.match.domain.match.MatchMinPlayerPolicy
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.platform.wiring.WiringContext

/** What the host needs after [MatchWiring.install]. */
public class MatchWiringResult(
    public val matches: IMatchRepository,
    public val lifecycle: IMatchLifecycle,
    private val shutdownHooks: List<() -> Unit>,
    private val scheduledTasks: List<IScheduler.TaskHandle>,
) {
    /**
     * Stops the replay engine and cancels scheduler tasks wiring started.
     * Subscriptions / registrations are closed by `WiringContext.close()`.
     */
    public fun shutdown() {
        shutdownHooks.forEach { runCatching(it) }
        scheduledTasks.forEach { runCatching { it.cancel() } }
    }
}

/** Values the per-area wiring functions share (replaces the old single-`onEnable` locals). */
internal class MatchWiringEnv(
    val ctx: WiringContext,
    val ports: MatchPlatformPorts,
    val matches: IMatchRepository,
    val stages: IStageRegistry,
    val eventBus: IDomainEventBus,
    val scheduler: IScheduler,
    val playerGateway: IPlayerGateway,
    val commandHandler: ICommandHandler,
    val permService: IPermissionService,
    val msgRenderer: IMessageRenderer,
    val matchModeRegistry: com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry,
    val facade: IMatchLifecycle,
    val queueSettings: QueueSettingsContents,
    val annihilationTracker: AnnihilationMatchTracker,
    val worldGateway: IWorldGateway?,
    val scheduledTasks: MutableList<IScheduler.TaskHandle>,
    val shutdownHooks: MutableList<() -> Unit>,
)

/**
 * Host-neutral Match wiring (ADR-0174). Pure construction / registration /
 * subscriptions / command specs — no Bukkit. Resolves the four lifecycle use
 * cases, exposes them via [IMatchLifecycle] on the ServiceRegistry, and
 * registers the `/astera-match` + `/astera-queue` command trees.
 *
 * Areas, in the original `onEnable` order: lifecycle + game modes (here),
 * [MatchWorldWiring], [MatchSessionWiring], [MatchGameplayWiring].
 *
 * The Persistence plugin registers Postgres-backed impls at higher priority
 * and `services.getOrThrow<IMatchRepository>()` then automatically returns
 * the Postgres version without any wiring change here.
 */
public object MatchWiring {

    public fun install(ctx: WiringContext, ports: MatchPlatformPorts): MatchWiringResult {
        val services = ctx.services
        val logger = ctx.logger
        val registrations = ctx.registrations
        val subscriptions = ctx.subscriptions
        val scheduledTasks = mutableListOf<IScheduler.TaskHandle>()
        val shutdownHooks = mutableListOf<() -> Unit>()

        // Wave L 5hardening-complete (ADR-0129): structured fan-out for
        // plugin onEnable failure. We do not wrap the whole onEnable in a
        // try-catch (Bukkit's own dispatcher already handles unhandled
        // throws by force-disabling the plugin); instead we expose a
        // helper closure that downstream blocks can wrap around their
        // risky bootstrap step (loadbefore Persistence missing, content
        // dir not writable, etc.) and report a Critical to the central
        // reporter when the cause is known structural.
        @Suppress("UNUSED_VARIABLE")
        val reportBootFailure: (String, Throwable) -> Unit = { phase, t ->
            services.get<com.astarworks.astera.core.application.port.outbound.error.IErrorReporter>()?.report(
                com.astarworks.astera.core.domain.error.ServerError.Critical(
                    occurredAt = java.time.Instant.now(),
                    summary = "AsteraMatchPlugin.onEnable failed in phase=$phase",
                    source = com.astarworks.astera.core.domain.error.ErrorSource.Lifecycle(
                        pluginName = "Astera-Match",
                        phase = phase,
                    ),
                    causeShortDescription = "${t::class.simpleName}: ${t.message ?: "<no message>"}",
                ),
            )
        }

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
        val stagesContentDir = ctx.contentDir.resolve("stages")
        val stageContents = StageContents()
        val stageReport = stageContents.bootstrap(stagesContentDir)
        logger.info("Loaded ${stageReport.loaded} stage(s) from $stagesContentDir (failed: ${stageReport.failed})")
        registrations += services.register<IStageRegistry>(stageContents, priority = 0)
        val stages: IStageRegistry = services.getOrThrow<IStageRegistry>()

        // Wave _infra-2b (ADR-0065): anomaly + generator template contents.
        // Both register on the /astera reload dispatcher; the runtime
        // IAnomalyScheduleRepository / IGeneratorRepository (per-match instances)
        // are wired separately further down (Wave 5c).
        val anomalyContentDir = ctx.contentDir.resolve("anomaly")
        val anomalyContents = AnomalyContents()
        val anomalyReport = anomalyContents.bootstrap(anomalyContentDir)
        logger.info("Loaded ${anomalyReport.loaded} anomaly schedule(s) from $anomalyContentDir (failed: ${anomalyReport.failed})")
        registrations += services.register<IAnomalyRegistry>(anomalyContents, priority = 0)

        val generatorContentDir = ctx.contentDir.resolve("generators")
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

        // WP8: queue lanes (stage / team sizes / min players / countdowns).
        val queueContentDir = ctx.contentDir.resolve("matchmaking")
        val queueSettings = QueueSettingsContents()
        val queueReport = queueSettings.bootstrap(queueContentDir)
        logger.info("Loaded ${queueReport.loaded} queue config(s) from $queueContentDir (failed: ${queueReport.failed})")
        reloadRegistry.register(queueSettings, queueContentDir)

        @Suppress("UnusedVariable")
        val broadcaster = services.getOrThrow<IBroadcaster>()

        // Wave I 5match-wireup-fix (ADR-0126): MatchEndSummaryListener consumes
        // IMessageRenderer for the per-player chat header / reason lines.
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
        // IWorldGateway is registered by Astera-Core (gate on null for hosts without one).
        val worldGateway = services.get<IWorldGateway>()
        // WP8: entry teleports the player to a stage spawn of their team, so a
        // queue-formed (or admin `entry`) player lands in the arena immediately.
        val matchSpawn = worldGateway?.let {
            MatchSpawnUseCase(
                matchRepository = matches,
                stageRegistry = stages,
                worldGateway = it,
                eventBus = eventBus,
            )
        }
        val entryGame = EntryGameUseCase(matches, eventBus, matchSpawn)
        val switchPhase = SwitchPhaseUseCase(
            matches, eventBus, scheduler, matchModeRegistry,
            minPlayersFor = { modeKey ->
                queueSettings.current().minPlayersForModeKey(modeKey)
                    ?: MatchMinPlayerPolicy.requirementFor(modeKey)
            },
        )

        // Wave 4.5 (ADR-0027): phase auto-timer + match spawn
        // Wave 6a-N1 (ADR-0154): MatchTimerService is the production
        // IMatchTimerService impl. Registered at priority 0 (no overlay
        // exists). PhaseTimerUseCase consults its per-match shift
        // accumulator when computing transition thresholds; ResolveAnomalyEffect
        // calls shiftPhaseTimer to extend / shorten the in-flight phase.
        val matchTimer = com.astarworks.astera.match.application.service.MatchTimerService(matches)
        registrations += services.register<com.astarworks.astera.match.application.port.outbound.IMatchTimerService>(
            matchTimer,
            priority = 0,
        )
        val phaseTimer = PhaseTimerUseCase(
            matches, scheduler, switchPhase, matchTimer,
            countdownTicks = { queueSettings.current().matchCountdownSeconds * 20L },
        )
        scheduler.runRepeatingTicks(initialDelayTicks = 20L, periodTicks = 20L) {
            phaseTimer.tickAll()
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

        val gameModeContentDir = ctx.contentDir.resolve("game-modes")
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

        // Wave 5c-N1 (ADR-0165) — six thin-façade IGameMode impls over the
        // legacy Wave 5c modes. Each façade publishes framework events on
        // lifecycle boundaries and otherwise no-ops (the `*Listener` classes
        // wired earlier in this onEnable carry the actual win-condition
        // logic). Programmatic registration follows the Norma fallback
        // pattern above — Wave 7a-N1 will land matching `content/game-modes/`
        // YAML, after which `register(...)` here overwrites the reflection-
        // built (bus-less) instances with these bus-wired ones.
        listOf(
            BattleLoyalGameMode(eventBus = eventBus),
            BreakCoreGameMode(eventBus = eventBus),
            CarryTntGameMode(eventBus = eventBus),
            CarryMiniTntGameMode(eventBus = eventBus),
            CaptureWoolGameMode(eventBus = eventBus),
            StealJewelryGameMode(eventBus = eventBus),
        ).forEach { gameModeFrameworkRegistry.register(it) }
        logger.info(
            "Wave 5c-N1 wiring: 6 IGameMode façades (battle-loyal / break-core / " +
                "carry-tnt / carry-mini-tnt / capture-wool / steal-jewelry) registered " +
                "into IGameModeFrameworkRegistry.",
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

            var gmCtx = FrameworkGameModeContext(
                matchId = event.matchId,
                modeId = mode.id(),
                participants = participants,
            )
            // Annihilation reads team ids from this key — seed it before
            // onMatchStarted fires so the tracker pillars are non-empty.
            if (modeId == AnnihilationGameMode.ID && teamIds.isNotEmpty()) {
                gmCtx = gmCtx.withState(AnnihilationGameMode.TEAMS_STATE_KEY, teamIds)
            }

            mode.onMatchStarted(gmCtx)
            gameModeScheduler.register(event.matchId, mode, gmCtx)
        }
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.PhaseChanged::class,
        ) { event ->
            // PhaseChanged carries no modeKey — fall back to Norma (Phase 1
            // PoC default; Wave 6 lands a per-match mode lookup on the event).
            val mode = gameModeFrameworkRegistry.get(NormaGameMode.ID) ?: return@subscribe
            val gmCtx = FrameworkGameModeContext(matchId = event.matchId, modeId = mode.id())
            val old = IGameModePhase.fromCore(event.from)
            val next = IGameModePhase.fromCore(event.to)
            mode.onPhaseChanged(gmCtx, old, next)
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
                val gmCtx = FrameworkGameModeContext(matchId = event.matchId, modeId = mode.id())
                mode.onMatchEnded(gmCtx)
            }
            gameModeScheduler.unregister(event.matchId)
        }

        // Wave 5a — friendly-fire team lookup adapter (Combat plugin resolves
        // IMatchTeamLookup via the shared registry to perform per-match team
        // checks without depending on Match directly).
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup>(
            com.astarworks.astera.match.application.service.MatchRepositoryTeamLookup(matches),
        )

        val env = MatchWiringEnv(
            ctx = ctx, ports = ports, matches = matches, stages = stages, eventBus = eventBus,
            scheduler = scheduler, playerGateway = playerGateway, commandHandler = commandHandler,
            permService = permService, msgRenderer = msgRenderer, matchModeRegistry = matchModeRegistry,
            facade = facade, queueSettings = queueSettings, annihilationTracker = annihilationTracker, worldGateway = worldGateway,
            scheduledTasks = scheduledTasks, shutdownHooks = shutdownHooks,
        )
        MatchWorldWiring.install(env)
        MatchSessionWiring.install(env)
        MatchGameplayWiring.install(env)

        return MatchWiringResult(
            matches = matches,
            lifecycle = facade,
            shutdownHooks = shutdownHooks.toList(),
            scheduledTasks = scheduledTasks.toList(),
        )
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
}
