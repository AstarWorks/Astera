package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.ProjectileImpactResolver
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.combat.domain.event.StatusExpired
import com.astarworks.astera.combat.domain.event.StatusRemoved
import com.astarworks.astera.combat.platform.wiring.CombatPlatformPorts
import com.astarworks.astera.combat.platform.wiring.CombatWiring
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.combat.application.service.VanillaKillResolver
import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.platform.wiring.CorePlatformPorts
import com.astarworks.astera.core.platform.wiring.CoreWiring
import com.astarworks.astera.core.platform.wiring.CoreWiringResult
import com.astarworks.astera.core.platform.wiring.GuiAdapterFactory
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.economy.platform.wiring.EconomyPlatformPorts
import com.astarworks.astera.economy.platform.wiring.EconomyWiring
import com.astarworks.astera.economy.platform.wiring.EconomyWiringResult
import com.astarworks.astera.items.platform.wiring.ItemsPlatformPorts
import com.astarworks.astera.items.platform.wiring.ItemsWiring
import com.astarworks.astera.items.platform.wiring.ItemsWiringResult
import com.astarworks.astera.match.platform.wiring.MatchPlatformPorts
import com.astarworks.astera.match.platform.wiring.MatchWiring
import com.astarworks.astera.match.platform.wiring.MatchWiringResult
import com.astarworks.astera.stats.platform.wiring.StatsPlatformPorts
import com.astarworks.astera.stats.platform.wiring.StatsWiring
import com.astarworks.astera.stats.platform.wiring.StatsWiringResult
import com.astarworks.astera.testfixtures.FakeBroadcaster
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A booted, headless Astera server (ADR-0174).
 *
 * [boot] builds one [ServiceRegistry] and runs the production wiring
 * functions in plugin enable order — `CoreWiring`, `ItemsWiring`,
 * `CombatWiring`, `MatchWiring`, `StatsWiring`, `EconomyWiring` — each with a
 * [WiringContext] exactly as the Paper plugins build them. Only the platform
 * port bundles differ: the Paper adapters are replaced by the `Sim*` ports.
 *
 * Time is virtual: nothing happens between [step] calls. Every stepped tick
 * ends with a trace snapshot.
 */
public class GameSim private constructor(
    public val scenarioName: String,
    public val world: SimWorld,
    public val scheduler: FakeScheduler,
    public val services: ServiceRegistry,
    public val recorder: TraceRecorder,
    public val server: SimMcServer,
    public val projectiles: SimProjectileService,
    public val broadcaster: FakeBroadcaster,
    private val contexts: List<WiringContext>,
) {
    /** Action bars / titles sent by mechanics (WP4). */
    public val feedback: SimPlayerFeedbackGateway = SimPlayerFeedbackGateway()

    public lateinit var core: CoreWiringResult
        private set
    public lateinit var items: ItemsWiringResult
        private set
    public lateinit var combat: CombatWiringResult
        private set
    public lateinit var match: MatchWiringResult
        private set
    public lateinit var stats: StatsWiringResult
        private set
    public lateinit var economy: EconomyWiringResult
        private set

    /** Every domain event published since boot, in order. */
    public val events: MutableList<DomainEvent> = mutableListOf()

    public val eventBus: IDomainEventBus get() = services.getOrThrow<IDomainEventBus>()

    public val tick: Long get() = scheduler.currentTick()

    // ---- Entities -------------------------------------------------------

    /**
     * Adds a player at [at] (default: standing on the ground at the origin) and
     * announces it like the Bukkit adapter would ([PlayerJoinedServer]) when [join].
     */
    public fun spawnPlayer(
        name: String,
        at: Vec3 = Vec3(0.0, world.groundY, 0.0),
        team: TeamId? = null,
        facing: Vec3 = Vec3(0.0, 0.0, 1.0),
        join: Boolean = true,
    ): SimEntity {
        val entity = world.add(SimEntity(newPlayerId(), name, SimEntityKind.PLAYER, at, facing.normalize(), team))
        recorder.registerEntity(entity)
        if (join) eventBus.publish(PlayerJoinedServer(playerId = entity.id))
        return entity
    }

    /** Adds a mob (addressed by id like a player; has an HP container and a hitbox). */
    public fun spawnMob(name: String, at: Vec3, team: TeamId? = null): SimEntity {
        val entity = world.add(SimEntity(newPlayerId(), name, SimEntityKind.MOB, at, Vec3(0.0, 0.0, 1.0), team))
        recorder.registerEntity(entity)
        return entity
    }

    /** Current Astera HP (the attribute container's `CURRENT_HP`, defaulting to max HP). */
    public fun hp(entity: SimEntity): Double? = hpOf(entity.id)

    public fun isAlive(id: PlayerId): Boolean = world.find(id) != null && (hpOf(id) ?: 1.0) > 0.0

    private fun hpOf(id: PlayerId): Double? {
        val store = services.get<IPlayerAttributeContainerStore>() ?: return null
        val container = store.containerFor(id)
        return (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value
            ?: (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value
            ?: DEFAULT_HP
    }

    // ---- Input ----------------------------------------------------------

    /** Gives [itemId] through the real `/astera-items give` use case; the first item given is held. */
    public fun give(entity: SimEntity, itemId: String): Boolean {
        val result = items.giveItem.execute(
            com.astarworks.astera.items.application.usecase.GiveItemUseCase.Request(null, entity.name, itemId),
        )
        return result is com.astarworks.astera.core.domain.Result.Success
    }

    /** Equips an item the entity already owns (or any registered item) as the held item. */
    public fun hold(entity: SimEntity, itemId: String) {
        entity.heldItem = ItemId(itemId)
        // What PaperHeldItemListener does on a hotbar change: the pool follows the held item.
        items.handleTrigger.onHeldItemChanged(entity.id, entity.heldItem)
    }

    /** The entity's resource pool (ammo / stamina / mana), or `null` before it joined. */
    public fun pool(entity: SimEntity): com.astarworks.astera.core.domain.resource.PlayerResourcePool? =
        services.get<com.astarworks.astera.core.application.port.outbound.IResourcePoolService>()?.currentPool(entity.id)

    /** Feeds one input with the held item into the same sink `PaperItemInputListener` feeds. */
    public fun input(entity: SimEntity, kind: IMcEvent.InputKind, sneaking: Boolean = false): Boolean {
        val view = checkNotNull(server.view(entity.id)) { "${entity.name} is not a player" }
        return items.triggerListener.handle(IMcEvent.PlayerInput(view, kind, sneaking, entity.heldItem))
    }

    public fun leftClick(entity: SimEntity): Boolean = input(entity, IMcEvent.InputKind.LEFT_CLICK)

    public fun rightClick(entity: SimEntity, sneaking: Boolean = false): Boolean =
        input(entity, IMcEvent.InputKind.RIGHT_CLICK, sneaking)

    /** The drop key (Q): bound to reload on guns. */
    public fun drop(entity: SimEntity): Boolean = input(entity, IMcEvent.InputKind.DROP)

    /**
     * Requests a skill cast the way an item binding does: publishes the
     * cross-plugin `SkillCastRequested` that Combat subscribes to. Use it for
     * skills no shipped item binds yet (staff-style casts).
     */
    public fun cast(entity: SimEntity, skillId: String, sourceItem: String = entity.heldItem?.value ?: "sim") {
        eventBus.publish(
            com.astarworks.astera.core.domain.event.SkillCastRequested(
                actorId = entity.id,
                skillId = com.astarworks.astera.core.domain.skill.SkillId(skillId),
                origin = entity.position,
                sourceItemId = ItemId(sourceItem),
                cooldownOverrideTicks = null,
            ),
        )
    }

    /** Raises (or lowers) the entity's max HP and fills it, via the registered health gateway. */
    public fun setMaxHealth(entity: SimEntity, maxHp: Double) {
        val health = services.getOrThrow<IPlayerHealthGateway>()
        health.setMaxHealth(entity.id, maxHp)
        health.setHealth(entity.id, maxHp, com.astarworks.astera.core.domain.player.physiology.HealthChangeSource.Other("sim-setup"))
    }

    // ---- Time -----------------------------------------------------------

    /** Advances virtual time by [ticks], one tick at a time, snapshotting after each. */
    public fun step(ticks: Int = 1) {
        require(ticks >= 0) { "ticks must be >= 0" }
        // The state the scenario set up before the first step is the trace's starting frame.
        if (ticks > 0 && !recorder.hasSnapshot(scheduler.currentTick())) {
            recorder.snapshot(scheduler.currentTick(), world.entities, ::hpOf, projectiles.snapshots())
        }
        repeat(ticks) {
            scheduler.advanceTicks(1)
            recorder.snapshot(scheduler.currentTick(), world.entities, ::hpOf, projectiles.snapshots())
        }
    }

    /** Steps until [condition] holds or [maxTicks] elapse. Returns whether it held. */
    public fun stepUntil(maxTicks: Int, condition: () -> Boolean): Boolean {
        repeat(maxTicks) {
            if (condition()) return true
            step(1)
        }
        return condition()
    }

    /** Events of type [T] published so far. */
    public inline fun <reified T : DomainEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

    // ---- Lifecycle ------------------------------------------------------

    /** Writes the JSON trace to `<traceDir>/<scenario>.json`. */
    public fun writeTrace(dir: Path = defaultTraceDir()): Path {
        // Make sure the last state is captured even if no tick was stepped.
        recorder.snapshot(scheduler.currentTick(), world.entities, ::hpOf, projectiles.snapshots())
        return recorder.write(dir)
    }

    public fun shutdown() {
        projectiles.stop()
        runCatching { economy.shutdown() }
        runCatching { stats.shutdown() }
        runCatching { match.shutdown() }
        runCatching { combat.shutdown() }
        contexts.asReversed().forEach { runCatching { it.close() } }
        runCatching { core.shutdown() }
    }

    public companion object {
        private const val DEFAULT_HP: Double = 20.0

        /** Per-tick HUD / regen chatter kept out of the trace's catch-all `events` list (still in [events]). */
        private val TRACE_NOISE: Set<String> = setOf("HudShown", "HudCleared", "HudUpdated", "ResourceRegenned")

        public fun defaultContentDir(): Path =
            Paths.get(System.getProperty("astera.sim.contentDir") ?: "content").toAbsolutePath().normalize()

        /**
         * Plugin names in the order [boot] installs them. `PluginLoadOrderTest`
         * asserts this is a valid topological order of the real `plugin.yml`
         * depend/softdepend graph, so a load-order bug cannot hide in the sim.
         */
        public val WIRING_ORDER: List<String> = listOf(
            "Astera-Core", "Astera-Items", "Astera-Combat", "Astera-Match", "Astera-Stats", "Astera-Economy",
        )

        public fun defaultTraceDir(): Path =
            Paths.get(System.getProperty("astera.sim.traceDir") ?: "build/sim-traces").toAbsolutePath().normalize()

        /** Boots the whole stack. [contentDir] defaults to the repository's real `content/`. */
        public fun boot(
            scenarioName: String,
            contentDir: Path = defaultContentDir(),
            groundY: Double = SimWorld.DEFAULT_GROUND_Y,
        ): GameSim {
            val logger = Logger.getLogger("astera-sim").apply { level = Level.WARNING }
            val world = SimWorld(groundY)
            val scheduler = FakeScheduler()
            val services = ServiceRegistry()
            val recorder = TraceRecorder(scenarioName, groundY)
            val mcServer = SimMcServer(world)
            val broadcaster = FakeBroadcaster()
            val contexts = mutableListOf<WiringContext>()
            fun context(): WiringContext = WiringContext(services, contentDir, logger).also { contexts += it }

            // Late-bound: the liveness test reads the attribute store Core registers.
            lateinit var sim: GameSim
            val alive: (PlayerId) -> Boolean = { sim.isAlive(it) }

            val worldGateway = RecordingWorldGateway(
                world = world,
                scheduler = scheduler,
                recorder = recorder,
                lobbySpawn = Vec3(0.0, groundY, 0.0),
                spectatorSpawn = Vec3(0.0, groundY + 10.0, 0.0),
            )

            val projectiles = SimProjectileService(
                world = world,
                scheduler = scheduler,
                recorder = recorder,
                isAlive = alive,
                resolver = { services.get<ProjectileImpactResolver>() },
            )

            sim = GameSim(scenarioName, world, scheduler, services, recorder, mcServer, projectiles, broadcaster, contexts)
            val feedback = sim.feedback

            // ---- Core -----------------------------------------------------
            val corePorts = CorePlatformPorts(
                mcServer = mcServer,
                scheduler = scheduler,
                broadcaster = broadcaster,
                commandHandler = RecordingCommandHandler(),
                guiAdapter = GuiAdapterFactory { _, _, _ -> FakeGuiAdapter() },
                worldGateway = { worldGateway },
            )
            val coreCtx = context()
            sim.core = CoreWiring.install(coreCtx, corePorts)

            // What AsteraCorePlugin.afterWiring registers on Paper (PaperPlayerPoseLookup),
            // plus the nearby-target query no Paper adapter registers yet.
            coreCtx.registrations += services.register<IPlayerPoseLookup>(SimPoseLookup(world), priority = 100)
            coreCtx.registrations += services.register<com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery>(
                SimNearbyTargetQuery(world, alive),
                priority = 100,
            )

            // Teams as declared on the simulated entities (Match registers its own, match-bound
            // lookup later; scenarios that never start a match still need ally / enemy answers).
            coreCtx.registrations += services.register<IMatchTeamLookup>(
                IMatchTeamLookup { id -> world.find(id)?.team },
                priority = 100,
            )

            // ---- Items ----------------------------------------------------
            sim.items = ItemsWiring.install(
                context(),
                ItemsPlatformPorts(
                    itemDelivery = { SimItemDelivery() },
                    itemIdResolver = SimItemIdResolver(world),
                ),
            )

            // ---- Combat ---------------------------------------------------
            val statusContainer = lazy {
                SimStatusEffectContainer(
                    scheduler = scheduler,
                    eventBus = services.getOrThrow<IDomainEventBus>(),
                    healthGateway = { services.get<IPlayerHealthGateway>() },
                )
            }
            sim.combat = CombatWiring.install(
                context(),
                CombatPlatformPorts(
                    projectileService = { projectiles },
                    damageGateway = { svc ->
                        SimDamageGateway(world, svc.getOrThrow<IPlayerAttributeContainerStore>(), scheduler)
                    },
                    statusEffectContainer = { statusContainer.value },
                    entityEffectsGateway = { SimEntityEffectsGateway(world) },
                    playerFeedbackGateway = { feedback },
                ),
            )
            // Post-wiring host step (CombatPaperPlatform.bind): tick loops and the
            // rerouted-damage kill publisher.
            projectiles.start()
            if (statusContainer.isInitialized()) statusContainer.value.start()
            val killResolver = VanillaKillResolver()
            val bus = services.getOrThrow<IDomainEventBus>()
            contexts.last().subscriptions += bus.subscribe(PlayerHealthChanged::class) { change ->
                killResolver.resolve(change, null)?.let { bus.publish(it) }
            }

            // ---- Match / Stats / Economy ---------------------------------
            val replayRoot = Files.createTempDirectory("astera-sim-replays")
            sim.match = MatchWiring.install(context(), MatchPlatformPorts(replayExportRoot = replayRoot))
            sim.stats = StatsWiring.install(context(), StatsPlatformPorts())
            sim.economy = EconomyWiring.install(context(), EconomyPlatformPorts())

            // ---- Trace taps ----------------------------------------------
            sim.attachTaps()
            return sim
        }
    }

    private fun attachTaps() {
        val bus = eventBus
        bus.subscribe(DomainEvent::class) { event ->
            events += event
            val type = event::class.simpleName ?: "event"
            if (type !in TRACE_NOISE) recorder.recordEvent(tick, type, event.toString())
        }
        bus.subscribe(DamageDealt::class) { e ->
            recorder.recordDamage(
                tick = tick,
                attacker = e.attacker,
                target = e.target,
                amount = e.resolved.finalAmount,
                source = e.source::class.simpleName ?: "unknown",
                hpAfter = hpOf(e.target),
            )
        }
        bus.subscribe(StatusApplied::class) { e ->
            recorder.recordStatus(tick, e.target, e.effect.spec.id.value, "applied", e.effect.currentStacks, e.effect.remainingTicks)
        }
        bus.subscribe(StatusRemoved::class) { e ->
            recorder.recordStatus(tick, e.target, e.effectId.value, "removed", null, null)
        }
        bus.subscribe(StatusExpired::class) { e ->
            recorder.recordStatus(tick, e.target, e.effectId.value, "expired", null, null)
        }
        bus.subscribe(PlayerKilled::class) { e ->
            recorder.recordDeath(tick, e.victim, e.killer, e.source::class.simpleName ?: "unknown")
        }
    }
}
