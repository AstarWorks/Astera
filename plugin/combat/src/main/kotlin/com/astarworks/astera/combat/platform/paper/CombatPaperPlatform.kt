package com.astarworks.astera.combat.platform.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.combat.application.service.VanillaKillResolver
import com.astarworks.astera.combat.adapter.paper.PaperDamageGateway
import com.astarworks.astera.combat.adapter.paper.PaperEnvironmentDamagePublisher
import com.astarworks.astera.combat.adapter.paper.PaperDisplayEntityGateway
import com.astarworks.astera.combat.adapter.paper.PaperEntityDamageGateway
import com.astarworks.astera.combat.adapter.paper.training.PaperTrainingDummyGuard
import com.astarworks.astera.combat.adapter.paper.training.PaperTrainingDummyShells
import com.astarworks.astera.combat.adapter.paper.PaperSummonGateway
import com.astarworks.astera.combat.adapter.paper.preview.PaperEntityCasterBody
import com.astarworks.astera.combat.adapter.paper.preview.PaperSkillPreviewStage
import com.astarworks.astera.combat.adapter.paper.preview.PaperTickRateControl
import com.astarworks.astera.combat.adapter.paper.SummonAllegianceListener
import com.astarworks.astera.combat.application.service.SummonRegistry
import com.astarworks.astera.combat.adapter.paper.PaperEntityEffectsGateway
import com.astarworks.astera.combat.adapter.paper.PaperPlayerFeedbackGateway
import com.astarworks.astera.combat.adapter.paper.PaperKnockbackGateway
import com.astarworks.astera.combat.adapter.paper.PaperNearbyTargetQuery
import com.astarworks.astera.combat.adapter.paper.PaperStatusEffectContainer
import com.astarworks.astera.combat.adapter.paper.PaperVanillaKillPublisher
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
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
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
 * The headless simulator replaces this class with its own ports.
 */
internal class CombatPaperPlatform(private val plugin: JavaPlugin, private val listeners: OwnedListeners) {

    // Concrete Paper instances the factories create; the post-wiring step
    // needs them (not just their port interface).
    private var explosionGateway: PaperExplosionGateway? = null
    private var entityDamageGateway: PaperEntityDamageGateway? = null

    private var statusTickHandle: IScheduler.TaskHandle? = null
    private var skillEngine: SkillEnginePaperRuntime? = null

    val ports: CombatPlatformPorts = CombatPlatformPorts(
        toolBlocks = { services ->
            com.astarworks.astera.combat.adapter.paper.PaperToolBlockGateway(
                plugin.server,
                { services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>() },
                com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer(
                    plugin, services.getOrThrow<com.astarworks.astera.core.application.service.item.ItemBlueprintFactory>(),
                ),
            )
        },
        nearbyTargetQuery = { PaperNearbyTargetQuery(plugin.server) },
        entityDamageGateway = {
            val gateway = PaperEntityDamageGateway(plugin.server)
            entityDamageGateway = gateway
            gateway
        },
        entityEffectsGateway = { PaperEntityEffectsGateway(plugin.server, plugin) },
        displayEntityGateway = { services ->
            val heldMaterializer = com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer(
                plugin, services.getOrThrow<com.astarworks.astera.core.application.service.item.ItemBlueprintFactory>(),
            )
            val hud = services.get<com.astarworks.astera.core.application.service.hud.HudPreferences>()
            PaperDisplayEntityGateway(
                server = plugin.server,
                plugin = plugin,
                scheduler = services.getOrThrow<IScheduler>(),
                intensityOf = { hud?.vfxIntensity(it) ?: com.astarworks.astera.core.domain.effect.VfxIntensity.FULL },
                heldStack = { viewer, item ->
                    // the held rig's weapon: the registered spec of the item that fired the cast, rendered like a given item (ADR-0276), never the live main hand
                    services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>()?.find(item)?.let { spec ->
                        heldMaterializer.buildFor(spec, viewer, 1)
                    }
                },
            )
        },
        summonGateway = { PaperSummonGateway(plugin.server) },
        trainingDummyShells = { PaperTrainingDummyShells(plugin) },
        entityCasterBody = { PaperEntityCasterBody(plugin.server, plugin) },
        skillPreviewStage = { PaperSkillPreviewStage(plugin, listeners) },
        tickRateControl = { PaperTickRateControl(plugin.server) },
        playerFeedbackGateway = { services -> PaperPlayerFeedbackGateway(plugin.server, services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IActionBarChannel>()) },
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
                services.getOrThrow<IDomainEventBus>(),
            )
            paperDamageGateway.start()
            paperDamageGateway
        },
        statusEffectContainer = { services ->
            // Carried across a rewire (ADR-0311): built once, by the first generation only.
            PaperStatusEffectContainer(
                server = plugin.server,
                scheduler = services.getOrThrow<IScheduler>(),
                eventBus = services.getOrThrow<IDomainEventBus>(),
                statusEffectRegistry = services.getOrThrow<IStatusEffectRegistry>(),
            )
        },
        wornItems = { com.astarworks.astera.combat.adapter.paper.PaperWornItems(plugin.server) },
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
        // The container is carried across generations, so it comes from the registry, not from a factory call.
        val statusContainer = services.get<com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer>() as? PaperStatusEffectContainer

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
                entityDamageGateway = services.get<com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway>(),
            )
            statusTickHandle = statusTickListener.start(plugin, listeners)
        }

        val applyDamage = wired.applyDamage
        val applyStatus = wired.applyStatus
        if (applyDamage != null && applyStatus != null) {
            // Cancels the vanilla BLOCK/ENTITY_EXPLOSION damage raised by its own
            // createExplosion call so explosion damage lands once (Astera path only).
            explosionGateway?.let { listeners.register(it) }
        }

        // WP5: summons never target or hurt their owner's side.
        services.get<SummonRegistry>()?.let { SummonAllegianceListener(it).start(plugin, listeners) }

        // Skill engine v2 (ADR-0301): v2 skills run through the cast runner; SkillCast is published once the gates pass.
        val container = statusContainer ?: error("skill engine v2 cannot start: the status effect container was not built")
        checkNotNull(applyDamage) { "skill engine v2 cannot start: applyDamage was not wired" }
        checkNotNull(applyStatus) { "skill engine v2 cannot start: applyStatus was not wired" }
        skillEngine = SkillEnginePaperRuntime(plugin, ports).also { it.start(ctx, applyDamage, applyStatus, container, wired) }

        // F3-11: lobby training dummies are immortal; vanilla damage on their bodies is cancelled.
        listeners.register(PaperTrainingDummyGuard())

        // WP3: cancel the vanilla copy of a melee hit from an Astera item so it
        // never double-applies next to the melee skill (ADR-0149).
        VanillaMeleeSuppressor(entityDamageGateway).start(plugin, listeners)

        // Wave 6a-N3 (ADR-0159): combat-side publisher for vanilla-intrusion
        // EnvironmentDamageApplied. Core BukkitEventAdapter (ADR-0153) Role 2
        // cancels + reroutes the EntityDamageEvent at EventPriority.LOW; this
        // publisher at LOWEST runs first and emits the combat-domain event
        // (Core cannot publish combat.* per ADR-0022 sibling-isolation).
        // Closes the last ADR-0134 deferred-events allowlist entry.
        PaperEnvironmentDamagePublisher(
            eventBus = eventBus,
            scheduler = scheduler,
        ).start(plugin, listeners)

        // Rerouted vanilla damage (melee / mob / environment / DoT) only moves
        // the Astera HP container; this publisher turns the >0 → <=0 edge of
        // PlayerHealthChanged into exactly one PlayerKilled.
        ctx.subscriptions += PaperVanillaKillPublisher(
            eventBus = eventBus,
            scheduler = scheduler,
            // ADR-0237: Match registers IMatchTeamLookup after Combat enables, so resolve per call.
            resolver = VanillaKillResolver(
                com.astarworks.astera.core.application.service.combat.CombatContextResolver {
                    services.get<com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup>()
                },
                recentAttacker = { victim, atTick ->
                    services.get<com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService>()
                        ?.entries(victim)
                        ?.lastOrNull { it.at >= atTick - VanillaKillResolver.VOID_ATTRIBUTION_TICKS }
                        ?.attackerId
                },
            ),
        ).start(plugin, listeners)
    }

    /** Cancels the scheduler-driven Paper pieces. Bukkit reclaims listeners via `HandlerList.unregisterAll(plugin)`. */
    fun teardown() {
        skillEngine?.stop()
        skillEngine = null
        statusTickHandle?.cancel()
        statusTickHandle = null
        explosionGateway = null
        entityDamageGateway = null
    }
}
