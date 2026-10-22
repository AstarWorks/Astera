package com.astarworks.astera.combat.platform.paper

import com.astarworks.astera.combat.adapter.paper.PaperDamageGateway
import com.astarworks.astera.combat.adapter.paper.PaperEnvironmentDamagePublisher
import com.astarworks.astera.combat.adapter.paper.PaperEntityDamageGateway
import com.astarworks.astera.combat.adapter.paper.PaperKnockbackGateway
import com.astarworks.astera.combat.adapter.paper.PaperNearbyTargetQuery
import com.astarworks.astera.combat.adapter.paper.PaperProjectileService
import com.astarworks.astera.combat.adapter.paper.PaperStatusEffectContainer
import com.astarworks.astera.combat.adapter.paper.PaperVanillaKillPublisher
import com.astarworks.astera.combat.adapter.paper.ProjectileHitListener
import com.astarworks.astera.combat.adapter.paper.StatusEffectTickListener
import com.astarworks.astera.combat.adapter.paper.VanillaMeleeSuppressor
import com.astarworks.astera.combat.adapter.paper.aoe.PaperAreaEffectGateway
import com.astarworks.astera.combat.adapter.paper.aoe.PaperExplosionGateway
import com.astarworks.astera.combat.adapter.paper.aoe.PaperFireSpreadController
import com.astarworks.astera.combat.adapter.paper.aoe.PaperLightningGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.application.service.EmptyNearbyTargetQuery
import com.astarworks.astera.combat.application.service.NoOpKnockbackGateway
import com.astarworks.astera.combat.platform.wiring.CombatPlatformPorts
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IDamageApplier
import com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler
import com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler
import com.astarworks.astera.core.application.port.outbound.effect.IStatusEffectApplier
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper half of Combat's enable (ADR-0174): builds the [CombatPlatformPorts]
 * the host-neutral `CombatWiring` consumes, and afterwards starts the
 * Bukkit-bound listeners that need objects the wiring built
 * (`ApplyDamageUseCase` / `ApplyStatusUseCase`).
 *
 * The headless simulator replaces this class: its own ports, plus a hit
 * dispatcher in place of `ProjectileHitListener`.
 */
internal class CombatPaperPlatform(private val plugin: JavaPlugin) {

    // Concrete Paper instances the factories create; the post-wiring step
    // needs them (not just their port interface).
    private var statusContainer: PaperStatusEffectContainer? = null
    private var projectileService: PaperProjectileService? = null
    private var explosionGateway: PaperExplosionGateway? = null
    private var entityDamageGateway: PaperEntityDamageGateway? = null

    private var statusTickHandle: IScheduler.TaskHandle? = null

    val ports: CombatPlatformPorts = CombatPlatformPorts(
        nearbyTargetQuery = { PaperNearbyTargetQuery(plugin.server) },
        entityDamageGateway = {
            val gateway = PaperEntityDamageGateway(plugin.server)
            entityDamageGateway = gateway
            gateway
        },
        projectileService = { services ->
            // Wave 6a-N2 (ADR-0155) — PaperProjectileService. Spawns vanilla
            // Bukkit projectile entities as visual carriers.
            val paperProjectile = PaperProjectileService(plugin.server, services.getOrThrow<IScheduler>())
            paperProjectile.start()
            projectileService = paperProjectile
            paperProjectile
        },
        lightningGateway = { services ->
            PaperLightningGateway(
                server = plugin.server,
                eventBus = services.getOrThrow<IDomainEventBus>(),
                scheduler = services.getOrThrow<IScheduler>(),
            )
        },
        fireSpreadController = { PaperFireSpreadController(plugin.server) },
        areaEffectGateway = { services ->
            PaperAreaEffectGateway(
                server = plugin.server,
                scheduler = services.getOrThrow<IScheduler>(),
                eventBus = services.getOrThrow<IDomainEventBus>(),
                nearbyQuery = services.get<INearbyTargetQuery>() ?: EmptyNearbyTargetQuery(),
                damageApplier = services.getOrThrow<IDamageApplier>(),
                statusApplier = services.getOrThrow<IStatusEffectApplier>(),
                worldGateway = services.getOrThrow<IWorldGateway>(),
            )
        },
        damageGateway = { services ->
            val attributeContainerStore = services.getOrThrow<IPlayerAttributeContainerStore>()
            val paperDamageGateway = PaperDamageGateway(
                plugin.server,
                services.getOrThrow<IScheduler>(),
                attributeContainerStore,
            )
            paperDamageGateway.start()
            paperDamageGateway
        },
        statusEffectContainer = { services ->
            val container = PaperStatusEffectContainer(
                server = plugin.server,
                scheduler = services.getOrThrow<IScheduler>(),
                eventBus = services.getOrThrow<IDomainEventBus>(),
                statusEffectRegistry = services.getOrThrow<IStatusEffectRegistry>(),
            )
            statusContainer = container
            container
        },
        knockbackGateway = { PaperKnockbackGateway(plugin.server) },
        explosionGateway = { services, damageApplier ->
            val gateway = PaperExplosionGateway(
                server = plugin.server,
                scheduler = services.getOrThrow<IScheduler>(),
                eventBus = services.getOrThrow<IDomainEventBus>(),
                nearbyQuery = services.get<INearbyTargetQuery>() ?: EmptyNearbyTargetQuery(),
                knockbackGateway = services.get<IKnockbackGateway>() ?: NoOpKnockbackGateway(),
                blockStateGateway = services.getOrThrow<IBlockStateGateway>(),
                damageApplier = damageApplier,
            )
            explosionGateway = gateway
            gateway
        },
    )

    /** Runs after `CombatWiring.install`. */
    fun bind(ctx: WiringContext, wired: CombatWiringResult) {
        val services = ctx.services
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val scheduler = services.getOrThrow<IScheduler>()

        // Wave 6a-N2 (ADR-0155) — StatusEffectTickListener drives Astera-side
        // tick effects (DoT damage / regen) + translates vanilla potion
        // removal (e.g. milk bucket) into Astera StatusRemoved events.
        // The IPlayerHealthGateway is resolved via the registry — Core's
        // priority-100 Paper impl (ADR-0151) wins automatically.
        statusContainer?.let { container ->
            val playerHealthGatewayForTicks = services.getOrThrow<IPlayerHealthGateway>()
            val statusTickListener = StatusEffectTickListener(
                server = plugin.server,
                scheduler = scheduler,
                eventBus = eventBus,
                container = container,
                playerHealthGateway = playerHealthGatewayForTicks,
            )
            statusTickHandle = statusTickListener.start(plugin)
        }

        val applyDamage = wired.applyDamage
        val applyStatus = wired.applyStatus
        val paperProjectile = projectileService
        if (applyDamage != null && applyStatus != null && paperProjectile != null) {
            // Cancels the vanilla BLOCK/ENTITY_EXPLOSION damage raised by its own
            // createExplosion call so explosion damage lands once (Astera path only).
            explosionGateway?.let { plugin.server.pluginManager.registerEvents(it, plugin) }

            // Wave 6a-N2 (ADR-0155): ProjectileHitListener bridges Bukkit
            // ProjectileHitEvent + EntityDamageByEntityEvent into the Astera
            // damage / status pipeline. Cancels vanilla EntityDamageByEntity
            // when damager is an Astera-tracked projectile (ADR-0149 §C —
            // prevents double-counting); non-Astera projectiles fall through
            // to Core's BukkitEventAdapter Role 2 vanilla-intrusion handler.
            val impactResolver = services.get<com.astarworks.astera.combat.application.service.ProjectileImpactResolver>()
                ?: error("ProjectileImpactResolver not registered — IParticleScheduler / ISoundScheduler missing (ADR-0109)")
            paperProjectile.bindImpactResolver(impactResolver)
            ProjectileHitListener(
                projectileService = paperProjectile,
                impactResolver = impactResolver,
            ).start(plugin)
        }

        // WP3: cancel the vanilla copy of a melee hit from an Astera item so it
        // never double-applies next to the melee skill (ADR-0149).
        VanillaMeleeSuppressor(entityDamageGateway).start(plugin)

        // Wave 6a-N3 (ADR-0159): combat-side publisher for vanilla-intrusion
        // EnvironmentDamageApplied. Core BukkitEventAdapter (ADR-0153) Role 2
        // cancels + reroutes the EntityDamageEvent at EventPriority.LOW; this
        // publisher at LOWEST runs first and emits the combat-domain event
        // (Core cannot publish combat.* per ADR-0022 sibling-isolation).
        // Closes the last ADR-0134 deferred-events allowlist entry.
        PaperEnvironmentDamagePublisher(
            eventBus = eventBus,
            scheduler = scheduler,
        ).start(plugin)

        // Rerouted vanilla damage (melee / mob / environment / DoT) only moves
        // the Astera HP container; this publisher turns the >0 → <=0 edge of
        // PlayerHealthChanged into exactly one PlayerKilled.
        ctx.subscriptions += PaperVanillaKillPublisher(
            eventBus = eventBus,
            scheduler = scheduler,
        ).start(plugin)
    }

    /** Cancels the scheduler-driven Paper pieces. Bukkit reclaims listeners via `HandlerList.unregisterAll(plugin)`. */
    fun teardown() {
        statusTickHandle?.cancel()
        statusTickHandle = null
        projectileService?.stop()
        projectileService = null
        statusContainer = null
        explosionGateway = null
        entityDamageGateway = null
    }
}
