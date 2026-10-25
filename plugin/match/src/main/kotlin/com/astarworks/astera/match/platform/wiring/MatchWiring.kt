package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.command.CommandMount
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
import com.astarworks.astera.match.application.gamemode.PveEnv
import com.astarworks.astera.match.application.gamemode.PveWaveGameMode
import com.astarworks.astera.match.application.gamemode.CarryMiniTntGameMode
import com.astarworks.astera.match.application.gamemode.CarryTntGameMode
import com.astarworks.astera.match.application.gamemode.SkirmishGameMode
import com.astarworks.astera.match.application.contents.NormaLayerContents
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.StealJewelryGameMode
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationGameMode
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationMatchTracker
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarWorld
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
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.service.AnomalyScheduler
import com.astarworks.astera.match.application.service.InMemoryAnomalyScheduleRepository
import com.astarworks.astera.match.application.service.InMemoryGeneratorRepository
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.StarGeneratorScheduler
import com.astarworks.astera.match.application.usecase.anomaly.ResolveAnomalyEffectUseCase
import com.astarworks.astera.match.application.usecase.anomaly.TriggerAnomalyUseCase
import com.astarworks.astera.match.application.usecase.generator.AdvanceGeneratorChargeUseCase
import com.astarworks.astera.match.application.usecase.generator.SpawnResourceUseCase
import com.astarworks.astera.match.application.usecase.match.SuddenDeathAcceleratorUseCase
import com.astarworks.astera.match.domain.mode.GameModeId
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
    /** Annihilation pillars as targets; the host feeds enemy block breaks into it (WP10). */
    public val pillarTargets: AnnihilationPillarTargets,
    /** Camera cycling for spectators; the host feeds left / right clicks into it (F3-5). `null` without an `ISpectator`. */
    public val spectatorCycler: com.astarworks.astera.match.application.service.death.SpectatorTargetCycler?,
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
    val commands: CommandMount,
    val permService: IPermissionService,
    val msgRenderer: IMessageRenderer,
    val matchModeRegistry: com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry,
    val facade: IMatchLifecycle,
    val queueSettings: QueueSettingsContents,
    val annihilationTracker: AnnihilationMatchTracker,
    val worldGateway: IWorldGateway?,
    /**
     * The Norma layer's config for a match: `content/norma-layer/layer.yaml` defaults overlaid with the `default_config`
     * of the game mode the match runs (the layer defaults alone when the mode is unknown); read live so reloads apply.
     */
    val modeConfigOf: (com.astarworks.astera.core.domain.match.MatchId) -> Map<String, String>,
    /**
     * Whether the Norma layer (stars, kill burst, Norma levels, time-limit ranking) runs in this match. It is a
     * standard layer of every game mode: on unless the mode's `default_config` says `norma_layer: "false"`. The
     * layer's tunables are the same merged config (`norma_*`, `time_limit_seconds`, `death_drop_ratio`).
     */
    val normaLayerOf: (com.astarworks.astera.core.domain.match.MatchId) -> Boolean,
    val scheduledTasks: MutableList<IScheduler.TaskHandle>,
    val shutdownHooks: MutableList<() -> Unit>,
) {
    /** Set by [MatchGameplayWiring]; `null` when the host registered no `ISpectator`. */
    var spectatorCycler: com.astarworks.astera.match.application.service.death.SpectatorTargetCycler? = null

    /** Set by [MatchGameplayWiring] on lobby backends (ADR-0248): the queue scheduler's countdown / hold state for the HUD. */
    var queueProgress: com.astarworks.astera.match.application.port.outbound.matchmaking.IQueueProgress? = null

    /** Set by [MatchGameplayWiring] (before the death coordinator): the match-scoped star store + sidebar gate. */
    var stars: MatchStarWiring.Wired? = null
}

/**
 * Host-neutral Match wiring (ADR-0174). Pure construction / registration /
 * subscriptions / command specs — no Bukkit. Resolves the four lifecycle use
 * cases, exposes them via [IMatchLifecycle] on the ServiceRegistry, and
 * registers the `/astera match` + `/astera queue` command trees.
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
        val commands = ctx.commandMount("Astera-Match")
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
        // WP9: one per-team spawn selector shared by entry teleports and respawns.
        val spawnSelector = com.astarworks.astera.match.application.service.DefaultSpawnSelector(matches)
        registrations += services.register<com.astarworks.astera.match.application.service.ISpawnSelector>(
            spawnSelector, priority = 0,
        )
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchEnded::class) {
            spawnSelector.forget(it.matchId)
        }
        // WP8: entry teleports the player to a stage spawn of their team, so a
        // queue-formed (or admin `entry`) player lands in the arena immediately.
        val matchSpawn = worldGateway?.let {
            MatchSpawnUseCase(
                matchRepository = matches,
                stageRegistry = stages,
                worldGateway = it,
                eventBus = eventBus,
                spawnSelector = spawnSelector,
            )
        }
        val entryGame = EntryGameUseCase(matches, eventBus, matchSpawn)
        // F1-D (ADR-0183): matches an admin forced past the roster floor.
        val minPlayerWaivers = com.astarworks.astera.match.application.admin.MatchMinPlayerWaivers()
        registrations += services.register(minPlayerWaivers)
        val switchPhase = SwitchPhaseUseCase(
            matches, eventBus, scheduler, matchModeRegistry,
            minPlayersFor = { modeKey ->
                queueSettings.current().minPlayersForModeKey(modeKey)
                    ?: MatchMinPlayerPolicy.requirementFor(modeKey)
            },
            minPlayersWaived = minPlayerWaivers::isWaived,
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
            // G1: the sudden-death timer must end the match for real (result screen, rewards, return to lobby).
            onSuddenDeathExpired = { id ->
                endMatch.execute(id, com.astarworks.astera.core.domain.event.EndReason.SuddenDeathResolved)
            },
            // A countdown stuck below the roster floor (opponents never arrived) ends instead of hanging:
            // everyone is told and sent back (lobby teleport / game-server transfer follow MatchEnded).
            onInsufficientPlayers = { id ->
                val roster = matches.findById(id)?.teams?.flatMap { it.members }.orEmpty()
                roster.forEach { member ->
                    playerGateway.sendMessage(
                        member,
                        msgRenderer.render(
                            member,
                            com.astarworks.astera.core.domain.i18n.MessageKey("astera.match.matchmaking.insufficient-cancelled"),
                            emptyMap(),
                        ),
                    )
                }
                endMatch.execute(id, com.astarworks.astera.core.domain.event.EndReason.Cancelled("insufficient-players"))
            },
        )
        scheduledTasks += scheduler.runRepeatingTicks(initialDelayTicks = 20L, periodTicks = 20L) {
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

        shutdownHooks += { gameModeScheduler.activeMatches().toList().forEach(gameModeScheduler::unregister) }
        val gameModeContentDir = ctx.contentDir.resolve("game-modes")
        val gameModeContents = GameModeContents()
        val gameModeReport = gameModeContents.bootstrap(gameModeContentDir)
        logger.info(
            "Loaded ${gameModeReport.loaded} game-mode spec(s) from $gameModeContentDir " +
                "(failed: ${gameModeReport.failed})",
        )
        // MODE-15: the Norma layer is not a mode. Its defaults live in content/norma-layer/layer.yaml and every mode
        // overrides them key by key in its own default_config.
        val normaLayerDir = ctx.contentDir.resolve("norma-layer")
        val normaLayer = NormaLayerContents()
        val normaLayerReport = normaLayer.bootstrap(normaLayerDir)
        logger.info("Loaded ${normaLayerReport.loaded} Norma layer config(s) from $normaLayerDir (failed: ${normaLayerReport.failed})")
        reloadRegistry.register(normaLayer, normaLayerDir)
        val modeConfigOf: (com.astarworks.astera.core.domain.match.MatchId) -> Map<String, String> = { id ->
            val key = matchModeRegistry.modeKeyOf(id)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { GameModeId(it) }.getOrNull() }
            normaLayer.configFor(key?.let { gameModeContents.find(it) }?.defaultConfig.orEmpty())
        }

        // Weapon restriction: the mode's `weapon_limits` applies to everyone inside a running match; Core enforces it
        // (entry trim + pickup / inventory-move gate) through this port.
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.item.IWeaponLimitProvider>(
            com.astarworks.astera.core.application.port.outbound.item.IWeaponLimitProvider { playerId ->
                matches.findMatchOf(playerId)?.let { match ->
                    com.astarworks.astera.core.domain.item.WeaponLimits.parse(
                        modeConfigOf(match.id)[com.astarworks.astera.core.domain.item.WeaponLimits.CONFIG_KEY],
                    )
                }
            },
            priority = 0,
        )

        // Reflective impl resolution (Phase 1 PoC; Koin replaces this in
        // Phase 3 per ADR-0106). Each spec whose impl_class resolves to a
        // concrete IGameMode singleton becomes available via the registry.
        val registered = gameModeContents.registerInto(gameModeFrameworkRegistry) { msg: String ->
            logger.warning(msg)
        }
        // Wave 5annihilation (ADR-0108) — Astera Annihilation mode foundation.
        // AnnihilationGameMode owns its own AnnihilationMatchTracker; unlike
        // the skirmish mode, the
        // tracker is the only writer of Annihilation match state and the
        // mode's onTick drives phase advances + sudden-death DoT.
        //
        // Replace the reflection-built (tracker-less + bus-less) instance with
        // a fully-wired one so framework events and tracker mutations actually
        // happen in production. Mirrors the Norma "replace YAML-resolved with
        // bus-wired" pattern above.
        val annihilationTracker = AnnihilationMatchTracker(eventBus)
        // WP10: pillars as combat / world targets. Combat resolves IStructureTargets
        // lazily (it wires before Match), so skills, projectiles and enemy block
        // breaks all reach the tracker through it.
        val pillarTargets = AnnihilationPillarTargets(annihilationTracker, matches, scheduler)
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.IStructureTargets>(
            pillarTargets,
        )
        val pillarWorld = worldGateway?.let {
            AnnihilationPillarWorld(annihilationTracker, it, matches, playerGateway, msgRenderer)
        }
        pillarWorld?.let { subscriptions += it.subscribe(eventBus) }
        val annihilationConfig = ports.annihilationConfig ?: run {
            val parsed = com.astarworks.astera.match.application.config.gamemode.annihilation
                .AnnihilationYamlOverlay.parse(
                    gameModeContents.find(AnnihilationGameMode.ID)?.defaultConfig ?: emptyMap(),
                )
            parsed.warnings.forEach { logger.warning(it) }
            parsed.config
        }
        gameModeFrameworkRegistry.register(
            AnnihilationGameMode(
                tracker = annihilationTracker,
                eventBus = eventBus,
                defaultConfig = annihilationConfig,
                clock = { scheduler.currentTick() },
            ),
        )
        logger.info(
            "Wave 5annihilation wiring: AnnihilationGameMode + AnnihilationMatchTracker " +
                "registered into IGameModeFrameworkRegistry.",
        )

        // WP11: the objective modes (break-core / carry-tnt / capture-wool /
        // steal-jewelry / battle-loyal) own their per-match state and react to
        // region / block / kill events of their own matches; the config comes
        // from content/game-modes/<id>.yaml `default_config`. carry-mini-tnt is
        // still an event-less façade (not wired to an objective yet).
        val siegeEnv = { id: com.astarworks.astera.match.domain.mode.GameModeId ->
            SiegeEnv(
                eventBus = eventBus,
                matches = matches,
                stages = stages,
                lifecycle = facade,
                world = worldGateway,
                messages = msgRenderer,
                players = playerGateway,
                config = normaLayer.configFor(gameModeContents.find(id)?.defaultConfig.orEmpty()),
                now = { scheduler.currentTick() },
            )
        }
        val siegeModes = listOf(
            SkirmishGameMode(siegeEnv(SkirmishGameMode.ID)),
            BattleLoyalGameMode(siegeEnv(BattleLoyalGameMode.ID)),
            BreakCoreGameMode(siegeEnv(BreakCoreGameMode.ID)),
            CarryTntGameMode(siegeEnv(CarryTntGameMode.ID)),
            CaptureWoolGameMode(siegeEnv(CaptureWoolGameMode.ID)),
            StealJewelryGameMode(siegeEnv(StealJewelryGameMode.ID)),
        )
        for (mode in siegeModes) {
            gameModeFrameworkRegistry.register(mode)
            subscriptions += mode.subscribe()
        }
        // ADR-0249: PvE waves. Mobs come from Core's IMobSpawner (real entities when a body gateway
        // is registered); without the spawner or the encounter registry the mode stays inert.
        val pveSpawner = services.get<com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner>()
        val pveEncounters = services.get<com.astarworks.astera.core.application.port.outbound.mob.IEncounterZoneRegistry>()
        val pveMode = PveWaveGameMode(
            env = siegeEnv(PveWaveGameMode.ID),
            pve = if (pveSpawner != null && pveEncounters != null) PveEnv(pveSpawner, pveEncounters) else null,
        )
        gameModeFrameworkRegistry.register(pveMode)
        subscriptions += pveMode.subscribeAll()
        gameModeFrameworkRegistry.register(CarryMiniTntGameMode(eventBus = eventBus))
        logger.info("WP11 wiring: ${siegeModes.size} objective game modes + carry-mini-tnt registered into IGameModeFrameworkRegistry.")

        reloadRegistry.register(gameModeContents, gameModeContentDir)
        logger.info(
            "Wave 5gamemode wiring complete: IGameModeFrameworkRegistry populated with " +
                "${gameModeFrameworkRegistry.all().size} mode(s) (YAML-resolved: " +
                "${registered.size}). DefaultGameModeScheduler registered.",
        )

        // Bridge: when a MatchStarted fires, resolve the match's mode via
        // [MatchStarted.modeKey] (Wave 5wireup §G16) and dispatch through
        // the framework hooks. `modeKey == null` keeps the legacy Norma
        // path — every existing caller (admin `/astera match start`,
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

            val modeId = resolveModeId(event.modeKey, gameModeFrameworkRegistry)
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
                // WP10: the stage tells the tracker where the pillars stand.
                match?.let { stages.find(it.stageId) }?.let {
                    gmCtx = gmCtx.withState(AnnihilationGameMode.STAGE_STATE_KEY, it)
                }
            }

            mode.onMatchStarted(gmCtx)
            if (modeId == AnnihilationGameMode.ID) pillarWorld?.placeAll(event.matchId)
            gameModeScheduler.register(event.matchId, mode, gmCtx)
        }
        subscriptions += eventBus.subscribe(
            com.astarworks.astera.match.domain.event.PhaseChanged::class,
        ) { event ->
            // PhaseChanged carries no modeKey: use the one MatchStarted mirrored for this match.
            val mode = gameModeFrameworkRegistry.get(
                resolveModeId(matchModeRegistry.modeKeyOf(event.matchId), gameModeFrameworkRegistry),
            ) ?: return@subscribe
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
            scheduler = scheduler, playerGateway = playerGateway, commands = commands,
            permService = permService, msgRenderer = msgRenderer, matchModeRegistry = matchModeRegistry,
            facade = facade, queueSettings = queueSettings, annihilationTracker = annihilationTracker, worldGateway = worldGateway,
            modeConfigOf = modeConfigOf,
            normaLayerOf = { id -> modeConfigOf(id)["norma_layer"] != "false" },
            scheduledTasks = scheduledTasks, shutdownHooks = shutdownHooks,
        )
        MatchWorldWiring.install(env)
        MatchSessionWiring.install(env)
        MatchGameplayWiring.install(env)

        return MatchWiringResult(
            matches = matches,
            lifecycle = facade,
            pillarTargets = pillarTargets,
            spectatorCycler = env.spectatorCycler,
            shutdownHooks = shutdownHooks.toList(),
            scheduledTasks = scheduledTasks.toList(),
        )
    }

    /**
     * Resolve the `MatchStarted.modeKey` string into the framework
     * [com.astarworks.astera.match.domain.mode.GameModeId] used by
     * [gameModeFrameworkRegistry] (Wave 5wireup §G16).
     *
     * `null` / empty / unregistered keys fall back to [SkirmishGameMode.ID] so any
     * call site that ships without a mode key plays the plain team skirmish.
     */
    private fun resolveModeId(
        modeKey: String?,
        registry: IGameModeFrameworkRegistry,
    ): com.astarworks.astera.match.domain.mode.GameModeId {
        val id = modeKey?.takeIf { it.isNotBlank() }
            ?.let { runCatching { GameModeId(it) }.getOrNull() }
        return if (id != null && registry.get(id) != null) id else SkirmishGameMode.ID
    }

}
