package com.astarworks.astera.combat.platform

import com.astarworks.astera.combat.application.contents.ArmorContents
import com.astarworks.astera.combat.application.contents.SkillContents
import com.astarworks.astera.combat.application.contents.StatusContents
import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IManaPoolService
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.application.service.ChargeTracker
import com.astarworks.astera.combat.application.service.EmptyNearbyTargetQuery
import com.astarworks.astera.combat.application.service.InMemoryDamageHistoryService
import com.astarworks.astera.combat.application.service.InMemoryManaPoolService
import com.astarworks.astera.combat.application.service.JumpCountTracker
import com.astarworks.astera.combat.application.usecase.equipment.ApplyArmorModifiersUseCase
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.combat.application.service.KotlinRandomDamageRandomizer
import com.astarworks.astera.combat.application.service.NoOpKnockbackGateway
import com.astarworks.astera.combat.application.service.StubArmorEquipmentProvider
import com.astarworks.astera.combat.application.service.StubCelEvaluator
import com.astarworks.astera.combat.application.service.YamlElementMatrix
import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.combat.application.usecase.charge.ChargedSkillCastUseCase
import com.astarworks.astera.combat.application.usecase.cost.ConsumeManaUseCase
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.damage.AssistAttributionUseCase
import com.astarworks.astera.combat.application.usecase.damage.RecordDamageHistoryOnDamageDealtListener
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.application.usecase.skill.dispatch.SkillCastDispatcher
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.application.usecase.status.TickCooldownUseCase
import com.astarworks.astera.combat.domain.rule.ArmorReductionModifier
import com.astarworks.astera.combat.domain.rule.CriticalStrikeModifier
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.HeadshotModifier
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.rule.KnockbackModifier
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.weapons.application.port.outbound.IProjectileService
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Combat plugin entrypoint (ADR-0022 Phase 4, expanded for Wave 5a).
 *
 * Wave 5a additions wired here:
 *  - `IArmorRegistry` + `ArmorLoaderService` (publishes registry, loads YAML)
 *  - `IManaPoolService` (in-memory default; Wave 5e adds persistence)
 *  - `IDamageRandomizer` (`KotlinRandomDamageRandomizer` default; tests bind `FakeDamageRandomizer`)
 *  - `IKnockbackGateway` (`NoOpKnockbackGateway` default; Wave 6 `PaperKnockbackGateway` overrides)
 *  - `IArmorEquipmentProvider` (`StubArmorEquipmentProvider` default; Wave 6 reads inventory)
 *  - `INearbyTargetQuery` (`EmptyNearbyTargetQuery` default; Wave 6 `PaperNearbyTargetQuery`)
 *  - `DamageRule` instance with full modifier pipeline
 *    (Headshot → ArmorReduction → CriticalStrike → Knockback) registered on
 *    the ServiceRegistry so Wave 6 adapters can resolve it
 *  - `ConsumeManaUseCase` constructed and threaded into `CastSkillUseCase`
 *  - `ChargedSkillCastUseCase` + `ChargeTracker`
 *  - `SkillCastDispatcher` subscribed to `SkillCast` with 6 archetype handlers
 *    (PROJECTILE/MELEE/AOE/BUFF/TELEPORT/SUMMON; SUMMON stubbed for Wave 6)
 *
 * Wave 6 will replace the stub/default impls (`StubArmorEquipmentProvider`,
 * `NoOpKnockbackGateway`, `EmptyNearbyTargetQuery`) with Paper-backed
 * versions registered ahead of this plugin's wiring step.
 */
public class AsteraCombatPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()
    private val subscriptions = mutableListOf<IDomainEventBus.Subscription>()

    override fun onEnable() {
        logger.info("Astera-Combat enabling…")

        // 1. Resolve Astera-Core ----------------------------------------------
        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Combat.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        // 2. Build Combat-owned impls ------------------------------------------
        // Wave _infra-2b (ADR-0065): SkillContents / StatusContents / ArmorContents
        // replace the legacy Mutable*Registry + *LoaderService pairs and participate
        // in the unified /astera reload <category> dispatcher.
        val skillsContentDir = core.contentDir.resolve("skills")
        val skillContents = SkillContents()
        val skillReport = skillContents.bootstrap(skillsContentDir)
        logger.info("Skills loaded: ${skillReport.loaded} ok, ${skillReport.failed} failed")

        val statusContentDir = core.contentDir.resolve("status")
        val statusContents = StatusContents()
        val statusReport = statusContents.bootstrap(statusContentDir)
        logger.info("Status effects loaded: ${statusReport.loaded} ok, ${statusReport.failed} failed")

        val elementMatrix: IElementMatrix = YamlElementMatrix.loadFromFile(
            core.contentDir.resolve("balance").resolve("element-matrix.yaml"),
        )

        // Wave 5a — Armor registry. Wave _infra-2b: ArmorContents (dual-store:
        // pieces + sets, atomic across both maps on reload).
        val armorContentDir = core.contentDir.resolve("armor")
        val armorContents = ArmorContents()
        val armorReport = armorContents.bootstrap(armorContentDir)
        logger.info("Armor specs loaded: ${armorReport.loaded} ok, ${armorReport.failed} failed")

        // Wave 5a — Mana pool service (in-memory; Wave 5e adds Postgres persistence)
        val manaService = InMemoryManaPoolService()

        // Wave 5a — Damage randomizer (Wave 6 may swap a tournament-specific seeded impl)
        val damageRandomizer = KotlinRandomDamageRandomizer()

        // Wave 5a-fix2 — sliding-window damage history for assist attribution (ADR-0063)
        val damageHistory = InMemoryDamageHistoryService()

        // 3. Register Combat ports onto the shared registry --------------------
        registrations += services.register<ISkillRegistry>(skillContents)
        registrations += services.register<IStatusEffectRegistry>(statusContents)
        registrations += services.register<IElementMatrix>(elementMatrix)
        registrations += services.register<IArmorRegistry>(armorContents)
        registrations += services.register<IManaPoolService>(manaService)
        registrations += services.register<IDamageRandomizer>(damageRandomizer)
        registrations += services.register<IDamageHistoryService>(damageHistory)

        // Wave 5f (ADR-0035, RTM #21): CEL evaluator port + Stub impl. Real
        // evaluator (JCEL / proxima / DIY) lands in a Phase 6 wave; Phase 1
        // ships the Stub so port consumers can wire against the surface.
        registrations += services.register<ICelEvaluator>(StubCelEvaluator())

        // Wave _infra-2b: register Combat's 3 content types on the dispatcher.
        val reloadRegistry = services.getOrThrow<IReloadableContentsRegistry>()
        reloadRegistry.register(skillContents, skillsContentDir)
        reloadRegistry.register(statusContents, statusContentDir)
        reloadRegistry.register(armorContents, armorContentDir)

        // 4. Wire use cases if runtime impls are present -----------------------
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val scheduler = services.getOrThrow<IScheduler>()
        val worldGateway = services.getOrThrow<IWorldGateway>()

        val cooldownTracker = services.get<ICooldownTracker>()
        if (cooldownTracker == null) {
            logger.warning(
                "ICooldownTracker not present — Astera-Weapons may not be loaded. " +
                    "CastSkillUseCase and TickCooldownUseCase will not be wired.",
            )
        }

        val damageGateway = services.get<IDamageGateway>()
        val statusContainer = services.get<IStatusEffectContainer>()

        // Wave 5a — DamageRule pipeline construction.
        // Critical strike defaults: 10% chance, 2.0× multiplier (Wave 7c will
        // load these from `content/balance/critical.yaml`).
        val knockbackGateway = services.get<IKnockbackGateway>() ?: NoOpKnockbackGateway()
        val armorProvider = services.get<IArmorEquipmentProvider>() ?: StubArmorEquipmentProvider()
        val nearbyQuery = services.get<INearbyTargetQuery>() ?: EmptyNearbyTargetQuery()
        val projectileService = services.get<IProjectileService>()
        val teamLookup = services.get<IMatchTeamLookup>()
        val damageRule = DamageRule(
            elementMatrix = elementMatrix,
            modifiers = listOf(
                HeadshotModifier(),
                ArmorReductionModifier(armorContents),
                CriticalStrikeModifier(critChance = 0.1, critMultiplier = 2.0),
                KnockbackModifier(),
            ),
            friendlyFire = FriendlyFirePolicy.BlockSameTeam,
            randomizer = damageRandomizer,
        )
        registrations += services.register<DamageRule>(damageRule)

        if (damageGateway != null && statusContainer != null && cooldownTracker != null) {
            val applyStatus = ApplyStatusUseCase(
                effectRegistry = statusContents,
                effectContainer = statusContainer,
                eventBus = eventBus,
            )
            val applyDamage = ApplyDamageUseCase(
                damageGateway = damageGateway,
                knockbackGateway = knockbackGateway,
                statusContainer = statusContainer,
                damageRule = damageRule,
                armorProvider = armorProvider,
                teamLookup = teamLookup,
                eventBus = eventBus,
                scheduler = scheduler,
                containerStore = services.get<IPlayerAttributeContainerStore>(),
            )
            val consumeMana = ConsumeManaUseCase(manaService, eventBus)
            val castSkill = CastSkillUseCase(
                skillRegistry = skillContents,
                statusContainer = statusContainer,
                eventBus = eventBus,
                cooldownTracker = cooldownTracker,
                scheduler = scheduler,
                consumeMana = consumeMana,
            )
            val chargeTracker = ChargeTracker(scheduler)
            @Suppress("UNUSED_VARIABLE")
            val chargedSkill = ChargedSkillCastUseCase(
                castSkill = castSkill,
                chargeTracker = chargeTracker,
                eventBus = eventBus,
                skillRegistry = skillContents,
                scheduler = scheduler,
            )

            @Suppress("UNUSED_VARIABLE")
            val tickCooldown = TickCooldownUseCase(
                cooldownTracker = cooldownTracker,
                scheduler = scheduler,
            )

            // Wave 5a — wire SkillCastDispatcher (subscribes to SkillCast).
            // Falls back to a no-op projectile service when Astera-Weapons isn't
            // loaded; the PROJECTILE handler then logs a warn and drops.
            val effectiveProjectileService = projectileService
                ?: object : IProjectileService {
                    override fun spawn(
                        spec: com.astarworks.astera.weapons.domain.projectile.ProjectileSpec,
                        origin: com.astarworks.astera.core.domain.geometry.Vec3,
                        direction: com.astarworks.astera.core.domain.geometry.Vec3,
                        owner: com.astarworks.astera.core.domain.player.PlayerId,
                    ): com.astarworks.astera.weapons.application.port.outbound.IProjectileHandle {
                        logger.fine("Projectile spawn no-op (Astera-Weapons absent)")
                        return NoOpProjectileHandle
                    }
                }
            val dispatcher = SkillCastDispatcher(
                skillRegistry = skillContents,
                applyDamage = applyDamage,
                applyStatus = applyStatus,
                worldGateway = worldGateway,
                projectileService = effectiveProjectileService,
                nearbyQuery = nearbyQuery,
                eventBus = eventBus,
            )
            subscriptions += dispatcher.start()

            // Wave 5a-fix2 — assist attribution pipeline (ADR-0063)
            val damageHistoryListener = RecordDamageHistoryOnDamageDealtListener(damageHistory)
            val assistAttribution = AssistAttributionUseCase(damageHistory, eventBus)
            subscriptions += eventBus.subscribe(DamageDealt::class) { damageHistoryListener.handle(it) }
            subscriptions += eventBus.subscribe(PlayerKilled::class) { assistAttribution.handle(it) }

            // Wave 5e: ApplyArmorModifiersUseCase + JumpCountTracker.
            // The use case is instantiated here for future Paper-side equip
            // wiring (Wave 6b's BukkitEventAdapter publishes LoadoutChanged
            // with the new full ArmorBundle); Wave 5e exposes it but does
            // not yet auto-subscribe.
            val containerStore = services.get<IPlayerAttributeContainerStore>()
            if (containerStore != null) {
                @Suppress("UNUSED_VARIABLE")
                val applyArmorModifiers = ApplyArmorModifiersUseCase(containerStore, armorContents)
            }
            @Suppress("UNUSED_VARIABLE") val jumpCountTracker = JumpCountTracker()

            logger.info(
                "Astera-Combat use cases wired: " +
                    "ApplyDamage, CastSkill (mana-aware), ApplyStatus, TickCooldown, " +
                    "ChargedSkillCast, SkillCastDispatcher (6 archetypes), " +
                    "DamageHistory + AssistAttribution, " +
                    "ApplyArmorModifiers (Wave 5e, ADR-0058), JumpCountTracker (Wave 5e).",
            )
        } else {
            logger.warning(
                "Combat use cases unwired until Stage 7-改. " +
                    "(IDamageGateway=${damageGateway != null}, " +
                    "IStatusEffectContainer=${statusContainer != null}, " +
                    "ICooldownTracker=${cooldownTracker != null})",
            )
        }

        logger.info(
            "Astera-Combat enabled. Services published: " +
                "ISkillRegistry, IStatusEffectRegistry, IElementMatrix, " +
                "IArmorRegistry, IManaPoolService, IDamageRandomizer, " +
                "IDamageHistoryService, DamageRule",
        )
    }

    override fun onDisable() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Combat disabled.")
    }

    private object NoOpProjectileHandle :
        com.astarworks.astera.weapons.application.port.outbound.IProjectileHandle {
        override val id = com.astarworks.astera.weapons.domain.projectile.ProjectileId(java.util.UUID(0, 0))
        override val isAlive: Boolean = false
        override fun cancel() = Unit
    }
}
