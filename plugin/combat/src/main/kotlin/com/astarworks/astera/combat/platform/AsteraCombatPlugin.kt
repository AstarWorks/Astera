package com.astarworks.astera.combat.platform

import com.astarworks.astera.combat.application.contents.ArmorContents
import com.astarworks.astera.combat.application.contents.ComboRouteContents
import com.astarworks.astera.combat.application.contents.SkillContents
import com.astarworks.astera.combat.application.contents.StatusContents
import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.IComboTracker
import com.astarworks.astera.combat.application.port.outbound.IFocusFireTracker
import com.astarworks.astera.combat.application.port.outbound.ITargetLockController
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IManaPoolService
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.config.StatusEffectInteractionLoader
import com.astarworks.astera.combat.application.service.YamlStatusEffectInteractionRule
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectInteractionRule
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IFireSpreadController
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
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
import com.astarworks.astera.combat.application.service.aoe.InMemoryAreaEffectGateway
import com.astarworks.astera.combat.application.service.aoe.InMemoryExplosionGateway
import com.astarworks.astera.combat.application.service.aoe.InMemoryFireSpreadController
import com.astarworks.astera.combat.application.service.aoe.InMemoryLightningGateway
import com.astarworks.astera.combat.application.service.combo.InMemoryComboTracker
import com.astarworks.astera.combat.application.service.damagebonus.ComboBonusSource
import com.astarworks.astera.combat.application.service.damagebonus.FocusFireBonusSource
import com.astarworks.astera.combat.application.service.damagebonus.TargetLockBonusSource
import com.astarworks.astera.combat.application.service.focus.InMemoryFocusFireTracker
import com.astarworks.astera.combat.application.service.targetlock.InMemoryTargetLockController
import com.astarworks.astera.combat.application.service.StubCelEvaluator
import com.astarworks.astera.combat.application.service.YamlElementMatrix
import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.usecase.charge.ChargedSkillCastUseCase
import com.astarworks.astera.combat.application.usecase.cost.ConsumeManaUseCase
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.damage.AssistAttributionUseCase
import com.astarworks.astera.combat.application.usecase.damage.RecordDamageHistoryOnDamageDealtListener
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.application.usecase.skill.dispatch.AoeHandler
import com.astarworks.astera.combat.application.usecase.skill.dispatch.BuffHandler
import com.astarworks.astera.combat.application.usecase.skill.dispatch.MeleeHandler
import com.astarworks.astera.combat.application.usecase.skill.dispatch.ProjectileHandler
import com.astarworks.astera.combat.application.usecase.skill.dispatch.SkillCastDispatcher
import com.astarworks.astera.combat.application.usecase.skill.dispatch.SummonHandler
import com.astarworks.astera.combat.application.usecase.skill.dispatch.TeleportHandler
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.application.usecase.status.TickCooldownUseCase
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
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
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.combat.application.port.outbound.IProjectileService
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

    /**
     * Wave 5match-hud (ADR-0118 §1) — owns the cooldown poll scheduler;
     * [onDisable] cancels via [HudUpdateOnCooldownTickListener.stop].
     */
    private var cooldownHudHandle:
        com.astarworks.astera.combat.application.eventbus.listeners.hud.HudUpdateOnCooldownTickListener? = null

    /**
     * Wave 6a-N2 (ADR-0155) — task handles for the three scheduler-driven
     * components: PaperStatusEffectContainer's mirror sweep, the per-tick
     * cooldown expiry pump, and PaperProjectileService's tracking watchdog.
     * Cancelled in [onDisable].
     */
    private var statusTickHandle: com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle? = null
    private var tickCooldownHandle: com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle? = null
    private var paperProjectileService: com.astarworks.astera.combat.adapter.paper.PaperProjectileService? = null

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

        // Wave 5o (ADR-0081) — status-effect interaction matrix.
        // YAML at content/balance/status-effect-interactions.yaml is loaded
        // through a tolerant loader (missing file → empty config → resolver
        // returns StatusInteraction.Pass for everything). Malformed YAML
        // throws at enable so the operator sees the problem immediately.
        val interactionConfig = StatusEffectInteractionLoader.load(
            core.contentDir.resolve("balance").resolve("status-effect-interactions.yaml"),
        )
        val statusInteractionRule: IStatusEffectInteractionRule =
            YamlStatusEffectInteractionRule(interactionConfig)
        logger.info(
            "Status-effect interaction rules loaded: ${interactionConfig.rules.size} rule(s).",
        )

        // 3. Register Combat ports onto the shared registry --------------------
        registrations += services.register<ISkillRegistry>(skillContents)
        registrations += services.register<IStatusEffectRegistry>(statusContents)
        registrations += services.register<IElementMatrix>(elementMatrix)
        registrations += services.register<IArmorRegistry>(armorContents)
        registrations += services.register<IManaPoolService>(manaService)
        registrations += services.register<IDamageRandomizer>(damageRandomizer)
        registrations += services.register<IDamageHistoryService>(damageHistory)
        registrations += services.register<IStatusEffectInteractionRule>(statusInteractionRule)

        // Wave 5f (ADR-0035, RTM #21): CEL evaluator port + Stub impl. Real
        // evaluator (JCEL / proxima / DIY) lands in a Phase 6 wave; Phase 1
        // ships the Stub so port consumers can wire against the surface.
        registrations += services.register<ICelEvaluator>(StubCelEvaluator())

        // Wave 5m (ADR-0088): Combat AOE + environment damage source.
        // Register InMemory defaults; Wave 6a `PaperExplosionGateway` /
        // `PaperAreaEffectGateway` / `PaperLightningGateway` /
        // `PaperFireSpreadController` overlay each at priority 100 to wire
        // the real Bukkit calls.
        val explosionGateway = InMemoryExplosionGateway(
            nearbyQuery = services.get<INearbyTargetQuery>() ?: EmptyNearbyTargetQuery(),
            knockbackGateway = services.get<IKnockbackGateway>() ?: NoOpKnockbackGateway(),
            eventBus = services.getOrThrow<IDomainEventBus>(),
            scheduler = services.getOrThrow<IScheduler>(),
        )
        val areaEffectGateway = InMemoryAreaEffectGateway(
            scheduler = services.getOrThrow<IScheduler>(),
            eventBus = services.getOrThrow<IDomainEventBus>(),
        )
        val lightningGateway = InMemoryLightningGateway(
            eventBus = services.getOrThrow<IDomainEventBus>(),
            scheduler = services.getOrThrow<IScheduler>(),
        )
        val fireSpread = InMemoryFireSpreadController()
        registrations += services.register<IExplosionGateway>(explosionGateway)
        registrations += services.register<IAreaEffectGateway>(areaEffectGateway)
        registrations += services.register<ILightningGateway>(lightningGateway)
        registrations += services.register<IFireSpreadController>(fireSpread)

        // Wave _infra-2b: register Combat's 3 content types on the dispatcher.
        val reloadRegistry = services.getOrThrow<IReloadableContentsRegistry>()
        reloadRegistry.register(skillContents, skillsContentDir)
        reloadRegistry.register(statusContents, statusContentDir)
        reloadRegistry.register(armorContents, armorContentDir)

        // ---- Wave 5combat-depth (ADR-0105) Combat Depth Foundation ----------
        //
        // Three session-scoped trackers + one YAML-backed combo registry:
        //   - IComboTracker: per-player combo timeline + route matching.
        //   - IFocusFireTracker: team-coordination damage windows.
        //   - ITargetLockController: ranged "lock-on" mechanic.
        //
        // None of the three persist (ADR-0105 §"No persistence"). Boss DSL
        // extensions (TimerExpired / MultiPlayerThreshold / MechanicCompleted
        // / AllOf) live in :plugin:core and are decodable from YAML today;
        // the lifecycle service learns to evaluate them in Wave 6b via
        // BossPhaseTransitionEvaluator (already wired by AsteraCorePlugin).
        val depthEventBus = services.getOrThrow<IDomainEventBus>()
        val comboRouteContents = ComboRouteContents()
        val comboContentDir = core.contentDir.resolve("combat-depth/combos")
        val comboReport = comboRouteContents.bootstrap(comboContentDir)
        logger.info("Combo routes loaded: ${comboReport.loaded} ok, ${comboReport.failed} failed")

        val comboTracker = InMemoryComboTracker(depthEventBus)
        comboRouteContents.all().forEach { comboTracker.registerRoute(it) }
        registrations += services.register<IComboTracker>(comboTracker)
        reloadRegistry.register(comboRouteContents, comboContentDir)

        val focusFireTracker = InMemoryFocusFireTracker(depthEventBus)
        registrations += services.register<IFocusFireTracker>(focusFireTracker)

        val targetLockController = InMemoryTargetLockController(depthEventBus)
        registrations += services.register<ITargetLockController>(targetLockController)

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

        // Wave Q 5port-fill (ADR-0133 §B): Phase 1 PoC InMemory default for
        // IDamageGateway. Returns TargetOffline for all calls; Wave 6a-3
        // PaperDamageGateway overlays at priority 100 below. Pre-stub
        // state null-guarded the entire ApplyDamageUseCase construction
        // (line ~297 below), leaving the combo / focus-fire / target-lock
        // damage bonus chain (Wave K / ADR-0128) dead in Phase 1 dev
        // sessions.
        registrations += services.register<IDamageGateway>(
            com.astarworks.astera.combat.application.service.InMemoryDamageGateway(),
            priority = 0,
        )

        // Wave 6a-redesign Step 3 (ADR-0151, supersedes ADR-0146): Paper
        // damage adapter now Astera-owns the math. Reads container CURRENT_HP,
        // subtracts finalAmount in Kotlin, writes back to container, mirrors
        // to Bukkit player.health clamped ≥ MIRROR_MIN_HP=1.0. NO call to
        // player.damage() (would double-count vanilla armor). NO setHealth(0)
        // (would fire vanilla PlayerDeathEvent). appliedThisTick marker
        // preserved for Wave 6a-6 BukkitEventAdapter Path 2 coordination.
        val attributeContainerStore =
            services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore>()
        val paperDamageGateway = com.astarworks.astera.combat.adapter.paper
            .PaperDamageGateway(server, scheduler, attributeContainerStore)
        paperDamageGateway.start()
        registrations += services.register<IDamageGateway>(
            paperDamageGateway,
            priority = 100,
        )

        // Wave 6a-N2 (ADR-0155) — PaperStatusEffectContainer overlays InMemory
        // at priority 100. Bukkit PotionEffect is UI mirror only per
        // ADR-0149 §C — Astera-internal map is source of truth. The companion
        // StatusEffectTickListener (constructed below the use-case wiring)
        // drives per-tick DoT/regen application + vanilla potion removal
        // translation.
        val paperStatusContainer = com.astarworks.astera.combat.adapter.paper
            .PaperStatusEffectContainer(
                server = server,
                scheduler = scheduler,
                eventBus = eventBus,
                statusEffectRegistry = statusContents,
            )
        registrations += services.register<IStatusEffectContainer>(
            paperStatusContainer,
            priority = 100,
        )

        // Wave 6a-N2 (ADR-0155) — StatusEffectTickListener drives Astera-side
        // tick effects (DoT damage / regen) + translates vanilla potion
        // removal (e.g. milk bucket) into Astera StatusRemoved events.
        // Started after PaperStatusEffectContainer is registered so the
        // listener's container ref + the registry resolution are stable.
        // The IPlayerHealthGateway is resolved via the registry — Core's
        // priority-100 Paper impl (ADR-0151) wins automatically.
        val playerHealthGatewayForTicks =
            services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway>()
        val statusTickListener = com.astarworks.astera.combat.adapter.paper
            .StatusEffectTickListener(
                server = server,
                scheduler = scheduler,
                eventBus = eventBus,
                container = paperStatusContainer,
                playerHealthGateway = playerHealthGatewayForTicks,
            )
        statusTickHandle = statusTickListener.start(this)

        val damageGateway = services.get<IDamageGateway>()
        val statusContainer = services.get<IStatusEffectContainer>()

        // Wave 6a-1 5paper-sweep-knockback (ADR-0144): Paper knockback
        // adapter overlays NoOpKnockbackGateway at priority 100. Reads
        // Bukkit Attribute.KNOCKBACK_RESISTANCE per ADR-0136 §A
        // gateway-boundary contract before scaling impulse + calling
        // setVelocity. NoOp at priority 0 remains as the unit-test /
        // non-Paper fallback (exempt from resistance contract per ADR-0136
        // §Exemption — no physical write).
        registrations += services.register<IKnockbackGateway>(
            com.astarworks.astera.combat.adapter.paper.PaperKnockbackGateway(server),
            priority = 100,
        )

        // Wave 5a — DamageRule pipeline construction.
        // Critical strike defaults: 10% chance, 2.0× multiplier (Wave 7c will
        // load these from `content/balance/critical.yaml`).
        val knockbackGateway = services.get<IKnockbackGateway>() ?: NoOpKnockbackGateway()
        val armorProvider = services.get<IArmorEquipmentProvider>() ?: StubArmorEquipmentProvider()
        val nearbyQuery = services.get<INearbyTargetQuery>() ?: EmptyNearbyTargetQuery()

        // Wave 6a-N2 (ADR-0155) — PaperProjectileService. Spawns vanilla
        // Bukkit projectile entities as visual carriers; damage resolution
        // is routed through ApplyDamageUseCase by ProjectileHitListener
        // (wired below) per ADR-0149 §C. No InMemory equivalent exists for
        // this port — Paper is the first impl; registered at priority 100
        // for consistency with the Wave 6a Paper sweep pattern.
        val paperProjectile = com.astarworks.astera.combat.adapter.paper
            .PaperProjectileService(server, scheduler)
        paperProjectile.start()
        paperProjectileService = paperProjectile
        registrations += services.register<IProjectileService>(paperProjectile, priority = 100)
        val projectileService: IProjectileService = paperProjectile
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
            // Wave K 5integration-wire (ADR-0128): the three combat-depth
            // trackers contribute IDamageBonusSource impls consulted by
            // ApplyDamageUseCase after rule resolution. Registration order
            // = consume order (combo first, then focus-fire, then
            // target-lock). The ComboBonusSource subscribes to
            // ComboRouteCompleted on construction; its subscription is
            // tracked here so onDisable can cancel it.
            val comboBonusSource = ComboBonusSource(eventBus)
            subscriptions += comboBonusSource.subscription
            val focusFireBonusSource = FocusFireBonusSource(focusFireTracker)
            val targetLockBonusSource = TargetLockBonusSource(targetLockController)
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
                bonusSources = listOf(
                    comboBonusSource,
                    focusFireBonusSource,
                    targetLockBonusSource,
                ),
            )
            val consumeMana = ConsumeManaUseCase(manaService, eventBus)

            // Wave _skills-2 (ADR-0077): MechanicDispatcher is the central
            // runtime for the Mechanic-Targeter-Phase architecture. Each
            // archetype handler routes per-phase mechanic lists through it.
            val celEvaluator = services.getOrThrow<ICelEvaluator>()
            val broadcaster = services.get<IBroadcaster>()
            // Wave 5effects (ADR-0109): optional pull-through of Core's
            // particle/sound infra so Mechanic.SpawnParticle + PlaySound stop
            // warn-logging and route through the real scheduler + registry.
            val particleScheduler =
                services.get<com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler>()
            val particleRegistry =
                services.get<com.astarworks.astera.core.application.port.outbound.IParticleRegistry>()
            val soundRegistry =
                services.get<com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry>()
            val soundScheduler =
                services.getOrThrow<com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler>()
            val particleSchedulerNonNull =
                particleScheduler
                    ?: error("IParticleScheduler not registered — required by ProjectileHitListener (ADR-0109)")
            val mechanicDispatcher = MechanicDispatcher(
                applyDamage = applyDamage,
                applyStatus = applyStatus,
                worldGateway = worldGateway,
                knockbackGateway = knockbackGateway,
                nearbyQuery = nearbyQuery,
                broadcaster = broadcaster,
                celEvaluator = celEvaluator,
                scheduler = scheduler,
                eventBus = eventBus,
                particleScheduler = particleScheduler,
                particleRegistry = particleRegistry,
                soundRegistry = soundRegistry,
            )
            registrations += services.register<MechanicDispatcher>(mechanicDispatcher)

            val castSkill = CastSkillUseCase(
                skillRegistry = skillContents,
                statusContainer = statusContainer,
                eventBus = eventBus,
                cooldownTracker = cooldownTracker,
                scheduler = scheduler,
                consumeMana = consumeMana,
                // skillCastDispatcher injected after construction below to
                // break the wiring cycle (dispatcher needs ProjectileHandler
                // which needs the projectile service which we resolve next).
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

            // Wave 6a-N2 (ADR-0155) — TickCooldownUseCase now publishes
            // CooldownExpired per expired (playerId, key) pair. Driven by
            // the per-tick scheduler so HUD / sound / skill-readiness
            // subscribers react without polling.
            val tickCooldown = TickCooldownUseCase(
                cooldownTracker = cooldownTracker,
                scheduler = scheduler,
                eventBus = eventBus,
            )
            tickCooldownHandle = scheduler.runRepeatingTicks(
                initialDelayTicks = 1L,
                periodTicks = 1L,
            ) { tickCooldown.execute() }

            // Wave 6a-N2 (ADR-0155): ProjectileHitListener bridges Bukkit
            // ProjectileHitEvent + EntityDamageByEntityEvent into the Astera
            // damage / status pipeline. Cancels vanilla EntityDamageByEntity
            // when damager is an Astera-tracked projectile (ADR-0149 §C —
            // prevents double-counting); non-Astera projectiles fall through
            // to Core's BukkitEventAdapter Role 2 vanilla-intrusion handler.
            val projectileHitListener = com.astarworks.astera.combat.adapter.paper
                .ProjectileHitListener(
                    projectileService = paperProjectile,
                    applyDamageUseCase = applyDamage,
                    applyStatusUseCase = applyStatus,
                    scheduler = scheduler,
                    particleScheduler = particleSchedulerNonNull,
                    soundScheduler = soundScheduler,
                )
            projectileHitListener.start(this)

            // Wave _skills-2 (ADR-0077) — archetype handlers, each holding a
            // reference to the shared MechanicDispatcher.
            val effectiveProjectileService: IProjectileService = projectileService
            val projectileHandler = ProjectileHandler(effectiveProjectileService, mechanicDispatcher)
            val meleeHandler = MeleeHandler(nearbyQuery, applyDamage, mechanicDispatcher)
            val aoeHandler = AoeHandler(nearbyQuery, applyDamage, mechanicDispatcher)
            val buffHandler = BuffHandler(applyStatus, scheduler, mechanicDispatcher)
            val teleportHandler = TeleportHandler(worldGateway, mechanicDispatcher)
            val summonHandler = SummonHandler(mechanicDispatcher)

            val dispatcher = SkillCastDispatcher(
                skillRegistry = skillContents,
                projectileHandler = projectileHandler,
                meleeHandler = meleeHandler,
                aoeHandler = aoeHandler,
                buffHandler = buffHandler,
                teleportHandler = teleportHandler,
                summonHandler = summonHandler,
                eventBus = eventBus,
            )
            subscriptions += dispatcher.start()

            // Wire CastSkillUseCase ↔ SkillCastDispatcher for PhaseOverrides
            // hand-off + MechanicDispatcher ↔ CastSkillUseCase for
            // Mechanic.CastSkill recursion (closing the loop).
            castSkill.bindSkillCastDispatcher(dispatcher)
            mechanicDispatcher.setCastSkillInvoker { invokedSkillId, _, ctx ->
                castSkill.execute(
                    casterId = ctx.actorId,
                    skillId = invokedSkillId,
                    at = ctx.origin,
                )
            }

            // Wave _items-1 (ADR-0073) — subscribe CastSkillUseCase to the
            // cross-plugin SkillCastRequested event. Items publishes this
            // when a bound input fires; we translate it into a
            // CastSkillUseCase.execute(...) call so combat continues to own
            // the silence / cooldown / mana pipeline. Wave _skills-2 carries
            // phase_overrides through.
            subscriptions += eventBus.subscribe(SkillCastRequested::class) { event ->
                castSkill.execute(
                    casterId = event.actorId,
                    skillId = event.skillId,
                    at = event.origin,
                    phaseOverrides = event.phaseOverrides,
                )
            }

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

        // Wave 5wireup §G8 — HUD listener for ComboStepRecorded lives in
        // combat per ADR-0022 sibling isolation (ComboStepRecorded is owned
        // by combat; Core cannot import it). The InMemory HUD gateway
        // records the call only until Wave 6b's Paper renderer lands.
        val hudGatewayOrNull = services.get<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        if (hudGatewayOrNull != null) {
            val hudComboListener = com.astarworks.astera.combat.application.eventbus.listeners.hud
                .HudUpdateOnComboStepRecordedListener(hudGatewayOrNull)
            subscriptions += eventBus.subscribe(
                com.astarworks.astera.combat.domain.event.ComboStepRecorded::class,
            ) { event -> hudComboListener.handle(event) }
            logger.info("Wave 5wireup §G8 wired: HudUpdateOnComboStepRecorded subscribed.")

            // Wave 5match-hud (ADR-0118 §1) — Cooldown HUD readout.
            //   ICooldownTracker has no Started/Tick event (intentional —
            //   tick storms would flood the bus). The listener polls the
            //   tracker every 5 ticks and pushes an action-bar update for
            //   each online player with active cooldowns.
            val sessionRepoOrNull = services.get<
                com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository>()
            if (cooldownTracker != null && sessionRepoOrNull != null) {
                val cooldownHudListener = com.astarworks.astera.combat.application.eventbus.listeners.hud
                    .HudUpdateOnCooldownTickListener(
                        cooldownTracker = cooldownTracker,
                        sessions = sessionRepoOrNull,
                        hudGateway = hudGatewayOrNull,
                        scheduler = scheduler,
                    )
                cooldownHudHandle = cooldownHudListener
                logger.info(
                    "Wave 5match-hud (ADR-0118 §1) wired: " +
                        "HudUpdateOnCooldownTick polling started.",
                )
            } else {
                logger.warning(
                    "Cooldown HUD listener skipped — ICooldownTracker=${cooldownTracker != null}, " +
                        "IPlayerSessionRepository=${sessionRepoOrNull != null}.",
                )
            }

            // Wave 5match-hud (ADR-0118 §3) — Status-effect sidebar.
            //   Subscribes to StatusApplied (:plugin:combat) and the Core
            //   StatusEffectCancelled. Sidebar recomputed from
            //   IStatusEffectContainer.active per push so the rendered
            //   slate stays consistent with the container's truth.
            if (statusContainer != null) {
                val statusHudListener = com.astarworks.astera.combat.application.eventbus.listeners.hud
                    .HudUpdateOnStatusEffectChangedListener(
                        statusEffectContainer = statusContainer,
                        hudGateway = hudGatewayOrNull,
                    )
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.combat.domain.event.StatusApplied::class,
                ) { event -> statusHudListener.onApplied(event) }
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.core.domain.event.StatusEffectCancelled::class,
                ) { event -> statusHudListener.onCancelled(event) }
                logger.info(
                    "Wave 5match-hud (ADR-0118 §3) wired: " +
                        "HudUpdateOnStatusEffectChanged subscribed (StatusApplied + StatusEffectCancelled).",
                )
            } else {
                logger.warning(
                    "Status-effect HUD listener skipped — IStatusEffectContainer not present.",
                )
            }
        } else {
            logger.warning(
                "IHudGateway not registered; HudUpdateOnComboStepRecordedListener skipped " +
                    "(astera-core must enable before astera-combat).",
            )
        }

        // ── Wave M 5polish-quickwin (ADR-0130 §C) — quickbar bind UI ─────
        // Wires the core-side BindQuickbarSlotIntentHandler with combat's
        // SkillContents as the skill-id provider. Lives here (not core
        // onEnable) because core cannot import combat — Konsist
        // sibling-isolation. The provider lambda re-reads `skillContents`
        // on every call so hot-reloaded skills surface immediately.
        val intentRouter = services.get<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>()
        val modalGateway = services.get<com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway>()
        val settingsRepo = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository>()
        val playerGatewayForBind = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerGateway>()
        val messageRendererForBind = services.get<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>()
        if (intentRouter != null && modalGateway != null && settingsRepo != null &&
            playerGatewayForBind != null && messageRendererForBind != null
        ) {
            val bindHandler = com.astarworks.astera.core.application.gui.lobby.intent
                .BindQuickbarSlotIntentHandler(
                    modalGateway = modalGateway,
                    settings = settingsRepo,
                    skillIdsProvider = { skillContents.all().map { it.id.value } },
                    playerGateway = playerGatewayForBind,
                    messages = messageRendererForBind,
                )
            registrations += intentRouter.register(
                com.astarworks.astera.core.application.gui.lobby.intent
                    .BindQuickbarSlotIntentHandler.INTENT_PREFIX,
            ) { intent, player -> bindHandler.handle(intent, player) }
            logger.info(
                "Wave M 5polish-quickwin (ADR-0130 §C) wired: " +
                    "BindQuickbarSlotIntentHandler registered under prefix " +
                    "${com.astarworks.astera.core.application.gui.lobby.intent.BindQuickbarSlotIntentHandler.INTENT_PREFIX}.",
            )
        } else {
            logger.warning(
                "Wave M 5polish-quickwin (ADR-0130 §C) skipped: " +
                    "one of IGuiIntentRouter / IGuiModalGateway / IPlayerSettingsRepository / " +
                    "IPlayerGateway / IMessageRenderer is not registered yet.",
            )
        }

        // Wave 6a-N3 (ADR-0159): combat-side publisher for vanilla-intrusion
        // EnvironmentDamageApplied. Core BukkitEventAdapter (ADR-0153) Role 2
        // cancels + reroutes the EntityDamageEvent at EventPriority.LOW; this
        // publisher at LOWEST runs first and emits the combat-domain event
        // (Core cannot publish combat.* per ADR-0022 sibling-isolation).
        // Closes the last ADR-0134 deferred-events allowlist entry.
        com.astarworks.astera.combat.adapter.paper.PaperEnvironmentDamagePublisher(
            eventBus = eventBus,
            scheduler = scheduler,
        ).start(this)

        logger.info(
            "Astera-Combat enabled. Services published: " +
                "ISkillRegistry, IStatusEffectRegistry, IElementMatrix, " +
                "IArmorRegistry, IManaPoolService, IDamageRandomizer, " +
                "IDamageHistoryService, DamageRule, " +
                "IExplosionGateway, IAreaEffectGateway, ILightningGateway, " +
                "IFireSpreadController (Wave 5m / ADR-0088), " +
                "IComboTracker, IFocusFireTracker, ITargetLockController " +
                "(Wave 5combat-depth / ADR-0105)",
        )
    }

    override fun onDisable() {
        cooldownHudHandle?.stop()
        cooldownHudHandle = null
        // Wave 6a-N2 (ADR-0155) — cancel the three scheduler-driven Paper
        // components. Bukkit reclaims the Listener registrations
        // automatically via HandlerList.unregisterAll(this).
        statusTickHandle?.cancel()
        statusTickHandle = null
        tickCooldownHandle?.cancel()
        tickCooldownHandle = null
        paperProjectileService?.stop()
        paperProjectileService = null
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Combat disabled.")
    }

    private object NoOpProjectileHandle :
        com.astarworks.astera.combat.application.port.outbound.IProjectileHandle {
        override val id = com.astarworks.astera.combat.domain.projectile.ProjectileId(java.util.UUID(0, 0))
        override val isAlive: Boolean = false
        override fun cancel() = Unit
    }
}
