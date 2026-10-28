package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.domain.damage.attackerIdOrNull
import com.astarworks.astera.combat.application.config.StatusEffectInteractionLoader
import com.astarworks.astera.combat.application.contents.ArmorContents
import com.astarworks.astera.combat.application.contents.ComboRouteContents
import com.astarworks.astera.combat.application.contents.StatusContents
import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.combat.application.port.outbound.IComboTracker
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IFocusFireTracker
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectInteractionRule
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.application.port.outbound.ITargetLockController
import com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IFireSpreadController
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.application.service.ChargeTracker
import com.astarworks.astera.combat.application.service.EmptyNearbyTargetQuery
import com.astarworks.astera.combat.application.service.InMemoryDamageHistoryService
import com.astarworks.astera.combat.application.service.JumpCountTracker
import com.astarworks.astera.combat.application.service.KotlinRandomDamageRandomizer
import com.astarworks.astera.combat.application.service.ArenaBlockEditGuard
import com.astarworks.astera.combat.application.service.SummonRegistry
import com.astarworks.astera.combat.application.service.NoOpKnockbackGateway
import com.astarworks.astera.combat.application.service.StubArmorEquipmentProvider
import com.astarworks.astera.combat.application.service.StubCelEvaluator
import com.astarworks.astera.combat.application.service.YamlElementMatrix
import com.astarworks.astera.combat.application.service.YamlStatusEffectInteractionRule
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
import com.astarworks.astera.combat.application.usecase.charge.ChargedSkillCastUseCase
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.damage.AssistAttributionUseCase
import com.astarworks.astera.combat.application.usecase.damage.RecordDamageHistoryOnDamageDealtListener
import com.astarworks.astera.combat.application.usecase.equipment.ApplyArmorModifiersUseCase
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.application.usecase.status.TickCooldownUseCase
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.rule.ArmorReductionModifier
import com.astarworks.astera.combat.domain.rule.CriticalStrikeModifier
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.HeadshotModifier
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.rule.KnockbackModifier
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.combat.application.contents.CombatBalanceContents
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.platform.wiring.WiringContext

/**
 * What the host needs after [CombatWiring.install]. The use-case members are
 * `null` when the guarded block was skipped (IDamageGateway / IStatusEffectContainer /
 * ICooldownTracker missing — same condition as before ADR-0174).
 */
public class CombatWiringResult(
    /** The cast gate's registry: every skill of the v2 catalog, its gate data read from the `meta:` block. */
    public val skillGate: SkillMetaRegistry,
    /** The v2 skill catalog (ADR-0301), loaded from `content/skills` at install; the Paper runtime casts from it. */
    public val skillCatalog: com.astarworks.astera.core.application.skillengine.load.SkillCatalog,
    /** Reloads `content/skills`; the Paper runtime calls it once the block registries exist so skills that named a custom block retry. */
    public val reloadSkills: () -> com.astarworks.astera.core.application.service.ContentLoader.LoadReport,
    /** Decoded `content/frames` point tables for the v2 compiler and point source. */
    public val frameTables: Map<String, com.astarworks.astera.core.application.skillengine.load.ItemFrames>,
    public val statusContents: StatusContents,
    public val armorContents: ArmorContents,
    public val applyDamage: ApplyDamageUseCase?,
    public val applyStatus: ApplyStatusUseCase?,
    public val castSkill: CastSkillUseCase?,
    /** F3-11: keeps the lobby training dummies alive; `null` when the host supplied no dummy bodies. */
    public val trainingRange: com.astarworks.astera.combat.application.service.training.TrainingRangeService?,
    private val shutdownHooks: List<() -> Unit>,
) {
    /**
     * Stops the scheduler-driven pieces wiring started (cooldown HUD poll,
     * per-tick cooldown expiry). Subscriptions / registrations are closed by
     * `WiringContext.close()`.
     */
    public fun shutdown() {
        shutdownHooks.forEach { runCatching(it) }
    }
}

/**
 * Host-neutral Combat wiring (ADR-0174). Pure construction / registration /
 * event-bus subscriptions — no Bukkit. Paper-only listeners (projectile hit
 * bridge, status tick sweep, vanilla damage/kill publishers, explosion event
 * registration) live in `platform/paper/CombatPaperPlatform`.
 */
public object CombatWiring {

    public fun install(ctx: WiringContext, ports: CombatPlatformPorts): CombatWiringResult {
        val services = ctx.services
        val logger = ctx.logger
        val registrations = ctx.registrations
        val subscriptions = ctx.subscriptions
        val shutdownHooks = mutableListOf<() -> Unit>()

        // 2. Build Combat-owned impls ------------------------------------------
        // Wave _infra-2b (ADR-0065): SkillContents / StatusContents / ArmorContents
        // replace the legacy Mutable*Registry + *LoaderService pairs and participate
        // in the unified /astera reload <category> dispatcher.
        val skillsContentDir = ctx.contentDir.resolve("skills")
        val contentCelEvaluator = services.get<ICelEvaluator>() ?: StubCelEvaluator()
        // Skill engine v2 (ADR-0301): the catalog is the one skill store; the cast gate reads its `meta:` blocks.
        val (frameTables, frameErrors) = com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
            .frameTables(ctx.contentDir.resolve("frames"))
        frameErrors.forEach { logger.warning("skill engine frame table: $it") }
        val skillCatalog = com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
            .catalog(frameTables, ports.blockKeys?.invoke(services) ?: com.astarworks.astera.core.application.skillengine.load.IBlockKeyCatalog.Unchecked)
        val skillEngineContents = com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents(skillCatalog)
        val skillGate = SkillMetaRegistry(skillCatalog)
        val skillReport = skillEngineContents.reload(skillsContentDir)
        logger.info("Skills (engine v2) loaded: ${skillReport.loaded} ok, ${skillReport.failed} failed")

        val statusContentDir = ctx.contentDir.resolve("status")
        val statusContents = StatusContents()
        val statusReport = statusContents.bootstrap(statusContentDir)
        logger.info("Status effects loaded: ${statusReport.loaded} ok, ${statusReport.failed} failed")

        val initialElementMatrix: IElementMatrix = YamlElementMatrix.loadFromFile(
            ctx.contentDir.resolve("balance").resolve("element-matrix.yaml"),
        )

        // Wave 5a — Armor registry. Wave _infra-2b: ArmorContents (dual-store:
        // pieces + sets, atomic across both maps on reload).
        val armorContentDir = ctx.contentDir.resolve("armor")
        val armorContents = ArmorContents()
        val armorReport = armorContents.bootstrap(armorContentDir)
        logger.info("Armor specs loaded: ${armorReport.loaded} ok, ${armorReport.failed} failed")

        // Wave 5a — Damage randomizer (Wave 6 may swap a tournament-specific seeded impl)
        // A host may pre-register a seeded source (the simulator does); it is then the single
        // random source for crits, status chances and weighted branches.
        val damageRandomizer = services.get<IDamageRandomizer>() ?: KotlinRandomDamageRandomizer()

        // Wave 5a-fix2 — sliding-window damage history for assist attribution (ADR-0063)
        val damageHistory = InMemoryDamageHistoryService()

        // Wave 5o (ADR-0081) — status-effect interaction matrix.
        // YAML at content/balance/status-effect-interactions.yaml is loaded
        // through a tolerant loader (missing file → empty config → resolver
        // returns StatusInteraction.Pass for everything). Malformed YAML
        // throws at enable so the operator sees the problem immediately.
        val interactionConfig = StatusEffectInteractionLoader.load(
            ctx.contentDir.resolve("balance").resolve("status-effect-interactions.yaml"),
        )
        // F3-9 (ADR-0224): both balance files are one reloadable category; consumers hold its stable views.
        val balanceContents = CombatBalanceContents(
            initialMatrix = initialElementMatrix,
            initialRule = YamlStatusEffectInteractionRule(interactionConfig),
        )
        val elementMatrix: IElementMatrix = balanceContents.elementMatrix
        val statusInteractionRule: IStatusEffectInteractionRule = balanceContents.interactionRule
        logger.info(
            "Status-effect interaction rules loaded: ${interactionConfig.rules.size} rule(s).",
        )

        // 3. Register Combat ports onto the shared registry --------------------
        registrations += services.register<ISkillRegistry>(skillGate)
        registrations += services.register<IStatusEffectRegistry>(statusContents)
        // Also published so Match can wipe fire / glow / freeze when a match ends or a player leaves it.
        val entityEffects = ports.entityEffectsGateway?.invoke(services)
        if (entityEffects != null) registrations += services.register<com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway>(entityEffects)
        registrations += services.register<IElementMatrix>(elementMatrix)
        registrations += services.register<IArmorRegistry>(armorContents)
        // Item tooltips: Core owns the layout, Combat supplies the skill lines from the v2 `meta:` blocks.
        services.get<com.astarworks.astera.core.application.service.item.ItemBlueprintFactory>()?.skillTooltips =
            services.get<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>()?.let {
                com.astarworks.astera.combat.application.service.SkillTooltipSummary(skillGate, it, armorContents)
            }
        if (services.get<IDamageRandomizer>() == null) {
            registrations += services.register<IDamageRandomizer>(damageRandomizer)
        }
        registrations += services.register<IDamageHistoryService>(damageHistory)
        registrations += services.register<IStatusEffectInteractionRule>(statusInteractionRule)

        // Wave 5f (ADR-0035, RTM #21): CEL evaluator port + Stub impl. Real
        // evaluator (JCEL / proxima / DIY) lands in a Phase 6 wave; Phase 1
        // ships the Stub so port consumers can wire against the surface.
        registrations += services.register<ICelEvaluator>(contentCelEvaluator)

        // WP3: host spatial query (players + mobs) before anything captures it.
        // WP10: wrapped so world structures (Annihilation pillars, IStructureTargets,
        // registered by Match later) are targets too.
        // F3-11: players inside the lobby training range are never offered as targets (no PvP there).
        val trainingDummies = com.astarworks.astera.combat.application.service.training.TrainingDummyRegistry()
        val trainingProtection = com.astarworks.astera.combat.application.service.training.TrainingRangeProtection(
            range = { services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>() },
            dummies = trainingDummies,
            poses = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup>() },
        )
        val baseNearbyQuery = ports.nearbyTargetQuery?.invoke(services)
            ?: services.get<INearbyTargetQuery>()
            ?: EmptyNearbyTargetQuery()
        registrations += services.register<INearbyTargetQuery>(
            com.astarworks.astera.combat.application.service.StructureAwareNearbyTargetQuery(
                base = com.astarworks.astera.combat.application.service.training.ProtectionAwareNearbyTargetQuery(
                    baseNearbyQuery,
                    trainingProtection,
                ),
                structures = { services.get<com.astarworks.astera.core.application.port.outbound.IStructureTargets>() },
            ),
            priority = 100,
        )

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

        // Wave 6d (ADR-0161): host overlays at priority 100. Paper's
        // PaperLightningGateway routes to `World.strikeLightning` (lethal) /
        // `strikeLightningEffect` (visual-only) per `damageEntities` flag.
        // PaperFireSpreadController toggles the DO_FIRE_TICK gamerule + walks
        // block radius for spreadFire (RADIUS_MAX=16, AIR-only ignition).
        ports.lightningGateway?.let {
            registrations += services.register<ILightningGateway>(it(services), priority = 100)
        }
        ports.fireSpreadController?.let {
            registrations += services.register<IFireSpreadController>(it(services), priority = 100)
        }

        // Wave 6e (ADR-0162): Paper overlays for Explosion + AreaEffect at
        // priority 100. PaperExplosionGateway uses `World.createExplosion`
        // for the visual blast + INearbyTargetQuery for Astera-owned entity
        // damage (vanilla damage cancelled by BukkitEventAdapter per ADR-0149 §C).
        // PaperAreaEffectGateway tick-drives shape-bound entity queries +
        // routes damage/status via the cross-plugin IDamageApplier /
        // IStatusEffectApplier SPI (registered by AsteraCorePlugin) + emits
        // particles via IWorldGateway.spawnParticle.
        // The explosion overlay registration is deferred until after
        // ApplyDamageUseCase is constructed (below) so the real
        // ExplosionDamageApplier can route entity damage through the full
        // modifier pipeline (Wave 6e-N1 / ADR-0164). Without this deferral
        // we'd be stuck with ExplosionDamageApplier.None (identity).
        ports.areaEffectGateway?.let {
            registrations += services.register<IAreaEffectGateway>(it(services), priority = 100)
        }

        // Wave _infra-2b: register Combat's 3 content types on the dispatcher.
        val reloadRegistry = services.getOrThrow<IReloadableContentsRegistry>()
        reloadRegistry.register(skillEngineContents, skillsContentDir)
        reloadRegistry.register(statusContents, statusContentDir)
        reloadRegistry.register(armorContents, armorContentDir)
        reloadRegistry.register(balanceContents, ctx.contentDir.resolve("balance"))

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
        val comboContentDir = ctx.contentDir.resolve("combat-depth/combos")
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
        // Preview dummies / mobs cast by entity uuid: widen the player-only pose + teleport reads to them.
        val entityBody = ports.entityCasterBody?.invoke(services)
        val worldGateway = services.getOrThrow<IWorldGateway>().let { base ->
            if (entityBody != null) com.astarworks.astera.combat.application.preview.EntityTeleportingWorldGateway(base, entityBody) else base
        }
        val casterPoses = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup>()?.let { base ->
            if (entityBody != null) com.astarworks.astera.combat.application.preview.EntityFallbackPoseLookup(base, entityBody) else base
        }

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
        ports.damageGateway?.let {
            registrations += services.register<IDamageGateway>(it(services), priority = 100)
        }

        // Wave 6a-N2 (ADR-0155) — host status container overlays InMemory
        // at priority 100. Bukkit PotionEffect is UI mirror only per
        // ADR-0149 §C — Astera-internal map is source of truth. (Paper's
        // companion StatusEffectTickListener is started by the Paper step.)
        ports.statusEffectContainer?.let {
            registrations += services.register<IStatusEffectContainer>(it(services), priority = 100)
        }

        // WP3: non-player (mob) damage. Optional: without a host impl mobs are
        // simply not damageable (ApplyDamageUseCase never takes the entity path).
        // WP10: always wrapped so structure ids (pillars) are damageable even without a host mob gateway.
        // F3-11: lobby training dummies take Astera damage on their own HP model (never die, regenerate).
        val trainingShells = ports.trainingDummyShells?.invoke(services)
        val trainingService = trainingShells?.let { shells ->
            com.astarworks.astera.combat.application.service.training.TrainingRangeService(
                range = { services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>() },
                dummies = trainingDummies,
                shells = shells,
                displays = ports.displayEntityGateway?.invoke(services),
                scheduler = services.getOrThrow<IScheduler>(),
                messages = services.get<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>(),
            ).also { service ->
                service.start()
                shutdownHooks += { service.stop() }
            }
        }
        registrations += services.register<IEntityDamageGateway>(
            com.astarworks.astera.combat.application.service.StructureAwareEntityDamageGateway(
                base = com.astarworks.astera.combat.application.service.training.TrainingDummyEntityDamageGateway(
                    base = ports.entityDamageGateway?.invoke(services) ?: services.get<IEntityDamageGateway>(),
                    dummies = trainingDummies,
                    now = { services.getOrThrow<IScheduler>().currentTick() },
                    onHit = { dummy, resolved, source ->
                        trainingService?.onHit(dummy, resolved, source)
                        val primary = when (source) {
                            is com.astarworks.astera.combat.domain.damage.DamageSource.Skill -> source.skillId.value == "sword-slash"
                            is com.astarworks.astera.combat.domain.damage.DamageSource.Item -> source.itemId.value == "knight-sword"
                            else -> false
                        }
                        if (primary) source.attackerIdOrNull()?.let { player ->
                            services.get<com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService>()?.recordAction(player, "primary")
                        }
                    },
                ),
                structures = { services.get<com.astarworks.astera.core.application.port.outbound.IStructureTargets>() },
            ),
            priority = 100,
        )
        val entityDamageGateway = services.get<IEntityDamageGateway>()

        val damageGateway = services.get<IDamageGateway>()
        val statusContainer = services.get<IStatusEffectContainer>()

        // F1-D (ADR-0183): admin god mode, consulted by ApplyDamageUseCase.
        val godModeService: com.astarworks.astera.combat.application.port.outbound.IGodModeService =
            services.get<com.astarworks.astera.combat.application.port.outbound.IGodModeService>()
                ?: com.astarworks.astera.combat.application.service.InMemoryGodModeService().also {
                    registrations += services.register<com.astarworks.astera.combat.application.port.outbound.IGodModeService>(it, priority = 0)
                }

        // Wave 6a-1 5paper-sweep-knockback (ADR-0144): Paper knockback
        // adapter overlays NoOpKnockbackGateway at priority 100. Reads
        // Bukkit Attribute.KNOCKBACK_RESISTANCE per ADR-0136 §A
        // gateway-boundary contract before scaling impulse + calling
        // setVelocity. NoOp at priority 0 remains as the unit-test /
        // non-Paper fallback (exempt from resistance contract per ADR-0136
        // §Exemption — no physical write).
        ports.knockbackGateway?.let {
            registrations += services.register<IKnockbackGateway>(it(services), priority = 100)
        }

        // Wave 5a — DamageRule pipeline construction.
        // Critical strike defaults: 10% chance, 2.0× multiplier (Wave 7c will
        // load these from `content/balance/critical.yaml`).
        val knockbackGateway = services.get<IKnockbackGateway>() ?: NoOpKnockbackGateway()
        val wornItems = ports.wornItems?.invoke(services)
        val armorProvider = services.get<IArmorEquipmentProvider>()
            ?: wornItems?.let {
                com.astarworks.astera.combat.application.service.ItemArmorEquipmentProvider(it, { services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>() }, armorContents)
            }
            ?: StubArmorEquipmentProvider()
        val nearbyQuery = com.astarworks.astera.combat.application.service.MeasuredNearbyTargetQuery(
            services.get<INearbyTargetQuery>() ?: EmptyNearbyTargetQuery(),
        )

        // Wave 6a-N2 (ADR-0155) — projectile service. Paper spawns vanilla
        // Bukkit projectile entities as visual carriers; damage resolution
        // is routed through ApplyDamageUseCase by ProjectileHitListener
        // (Paper step) per ADR-0149 §C. No InMemory equivalent exists for
        // this port — registered at priority 100 for consistency with the
        // Wave 6a Paper sweep pattern.
        val projectileService: IProjectileService = ports.projectileService(services)
        registrations += services.register<IProjectileService>(projectileService, priority = 100)
        // Match enables after Combat; resolve its team service when damage is applied.
        val teamLookup = IMatchTeamLookup { playerId -> services.get<IMatchTeamLookup>()?.teamOf(playerId) }
        val combatContexts = com.astarworks.astera.core.application.service.combat.CombatContextResolver { services.get<IMatchTeamLookup>() }
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

        var applyDamageOut: ApplyDamageUseCase? = null
        var applyStatusOut: ApplyStatusUseCase? = null
        var castSkillOut: CastSkillUseCase? = null

        if (damageGateway != null && statusContainer != null && cooldownTracker != null) {
            val applyStatus = ApplyStatusUseCase(
                effectRegistry = statusContents,
                effectContainer = statusContainer,
                scheduler = scheduler,
            )
            // Wave K 5integration-wire (ADR-0128): the three combat-depth
            // trackers contribute IDamageBonusSource impls consulted by
            // ApplyDamageUseCase after rule resolution. Registration order
            // = consume order (combo first, then focus-fire, then
            // target-lock). The ComboBonusSource subscribes to
            // ComboRouteCompleted on construction; its subscription is
            // tracked here so the context close can cancel it.
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
                entityDamageGateway = entityDamageGateway,
                godMode = godModeService,
                protection = trainingProtection,
                // Core registers the gateway before Combat enables; resolved per call anyway.
                spawnProtection = com.astarworks.astera.combat.application.port.outbound.IDamageProtection { target ->
                    services.get<com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway>()?.isProtected(target) == true
                },
                // ADR-0237: Match registers IMatchTeamLookup after Combat enables, so resolve per call.
                contexts = combatContexts,
            )

            // Wave 6e-N1 (ADR-0164): the explosion overlay is wired with
            // ApplyDamageExplosionDamageApplier so explosion entity damage
            // flows through the full Astera modifier pipeline (armor / crit /
            // friendly-fire / combo / focus-fire). Replaces the placeholder
            // ExplosionDamageApplier.None that Wave 6e shipped at Phase C.
            val explosionDamageApplier =
                com.astarworks.astera.combat.application.service.aoe.ApplyDamageExplosionDamageApplier(applyDamage)
            ports.explosionGateway?.let {
                registrations += services.register<IExplosionGateway>(
                    it(services, explosionDamageApplier),
                    priority = 100,
                )
            }

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
            val summonRegistry = SummonRegistry { services.get<IMatchTeamLookup>() }
            registrations += services.register<SummonRegistry>(summonRegistry)
            // Shared by every host: Paper's ProjectileHitListener and the
            // headless simulator's projectile stepper both resolve impacts here.
            // ADR-0280: opt-in cover damage / placement for skills (the CoverBlockService is Core's, resolved lazily).
            val coverEffects = com.astarworks.astera.combat.application.service.SkillCoverEffects(
                cover = { services.get<com.astarworks.astera.core.application.service.CoverBlockService>() },
            )
            val particleSchedulerForImpacts = particleScheduler
            val soundSchedulerForImpacts =
                services.get<com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler>()
            if (particleSchedulerForImpacts != null && soundSchedulerForImpacts != null) {
                registrations += services.register<com.astarworks.astera.combat.application.service.ProjectileImpactResolver>(
                    com.astarworks.astera.combat.application.service.ProjectileImpactResolver(
                        applyDamage = applyDamage,
                        applyStatus = applyStatus,
                        scheduler = scheduler,
                        particleScheduler = particleSchedulerForImpacts,
                        soundScheduler = soundSchedulerForImpacts,
                        structures = { services.get<com.astarworks.astera.core.application.port.outbound.IStructureTargets>() },
                        coverEffects = coverEffects,
                    ),
                )
            }

            val castSkill = CastSkillUseCase(
                skillRegistry = skillGate,
                statusContainer = statusContainer,
                eventBus = eventBus,
                cooldownTracker = cooldownTracker,
                scheduler = scheduler,
                resourcePool = services.get<com.astarworks.astera.core.application.port.outbound.IResourcePoolService>(),
                items = services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>(),
                poseLookup = casterPoses,
                egoGauge = services.get<com.astarworks.astera.core.application.port.outbound.IEgoGauge>(),
                // which needs the projectile service which we resolve next).
            )
            val chargeTracker = ChargeTracker(scheduler)
            @Suppress("UNUSED_VARIABLE")
            val chargedSkill = ChargedSkillCastUseCase(
                castSkill = castSkill,
                chargeTracker = chargeTracker,
                eventBus = eventBus,
                skillRegistry = skillGate,
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
            val tickCooldownHandle = scheduler.runRepeatingTicks(ExecutionScope.Global,
                initialDelayTicks = 1L,
                periodTicks = 1L,
            ) { tickCooldown.execute() }
            shutdownHooks += { tickCooldownHandle.cancel() }

            // Wave _items-1 (ADR-0073) — subscribe CastSkillUseCase to the
            // cross-plugin SkillCastRequested event. Items publishes this
            // when a bound input fires; we translate it into a
            // CastSkillUseCase.execute(...) call so combat continues to own
            // the silence / cooldown / resource pipeline. Wave _skills-2 carries
            // phase_overrides through.
            subscriptions += eventBus.subscribe(SkillCastRequested::class) { event ->
                val dbg = castSkill.execute(
                    casterId = event.actorId,
                    skillId = event.skillId,
                    at = event.origin,
                    sourceItemId = event.sourceItemId,
                    cooldownOverrideTicks = event.cooldownOverrideTicks,
                    trigger = event.trigger,
                )
                logger.fine("cast ${event.skillId} by ${event.actorId} -> $dbg")
            }

            // WP2 — player-facing feedback when a cast is refused (empty
            // magazine, no energy, mid-reload, silenced).
            services.get<com.astarworks.astera.core.application.port.outbound.IHudGateway>()?.let { hud ->
                val feedback = com.astarworks.astera.combat.application.eventbus.listeners.hud
                    .CastRejectedFeedbackListener(hud)
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.combat.domain.event.SkillCastRejected::class,
                ) { event -> feedback.handle(event) }
            }

            // Wave 5a-fix2 — assist attribution pipeline (ADR-0063)
            val damageHistoryListener = RecordDamageHistoryOnDamageDealtListener(damageHistory)
            val assistAttribution = AssistAttributionUseCase(damageHistory, eventBus)
            subscriptions += eventBus.subscribe(DamageDealt::class) { damageHistoryListener.handle(it) }
            subscriptions += eventBus.subscribe(PlayerKilled::class) { assistAttribution.handle(it) }

            // ADR-0231 — E.G.O gauge: fills on hits dealt / taken, empties on death.
            services.get<com.astarworks.astera.core.application.port.outbound.IEgoGauge>()?.let { gauge ->
                val egoListener = com.astarworks.astera.combat.application.eventbus.listeners.ego
                    .EgoGaugeCombatListener(gauge)
                subscriptions += eventBus.subscribe(DamageDealt::class) { egoListener.onDamage(it) }
                subscriptions += eventBus.subscribe(PlayerKilled::class) { egoListener.onKilled(it) }
            }
            if (statusContainer != null) {
                val clearStatuses = com.astarworks.astera.combat.application.eventbus.listeners.status
                    .ClearStatusesOnPlayerKilledListener(statusContainer)
                subscriptions += eventBus.subscribe(PlayerKilled::class) { clearStatuses.handle(it) }
            }

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
                    "ApplyDamage, CastSkill (resource-aware), ApplyStatus, TickCooldown, " +
                    "ChargedSkillCast, " +
                    "DamageHistory + AssistAttribution, " +
                    "ApplyArmorModifiers (Wave 5e, ADR-0058), JumpCountTracker (Wave 5e).",
            )

            applyDamageOut = applyDamage
            applyStatusOut = applyStatus
            castSkillOut = castSkill
        } else {
            logger.warning(
                "Combat use cases unwired until Stage 7-改. " +
                    "(IDamageGateway=${damageGateway != null}, " +
                    "IStatusEffectContainer=${statusContainer != null}, " +
                    "ICooldownTracker=${cooldownTracker != null})",
            )
        }

        CombatHudWiring.install(
            ctx = ctx,
            skills = skillGate,
            cooldownTracker = cooldownTracker,
            statusContainer = statusContainer,
            eventBus = eventBus,
            scheduler = scheduler,
            feedbackGateway = ports.playerFeedbackGateway?.invoke(services),
            shutdownHooks = shutdownHooks,
        )

        // ── F1-D (ADR-0183) — `/astera admin panel` combat tools ─────────────────
        val adminTools = com.astarworks.astera.combat.application.admin.AdminCombatToolsUseCase(
            health = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway>(),
            statuses = statusContainer,
            cooldowns = cooldownTracker,
            resources = services.get<com.astarworks.astera.core.application.port.outbound.IResourcePoolService>(),
            godMode = godModeService,
        )
        registrations += services.register(adminTools)
        registrations += com.astarworks.astera.combat.application.admin.AdminCombatPanel(
            guard = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.AdminPanelGuard>(),
            registry = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry>(),
            tools = adminTools,
            mcServer = services.getOrThrow<com.astarworks.astera.core.adapter.api.server.IMcServer>(),
        ).install(services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>())

        // ── F2-5 (ADR-0196) — cast any skill directly: `/astera skill ...` + admin panel Skills page ──
        val adminSkillCaster = com.astarworks.astera.combat.application.admin.AdminSkillCaster(
            skills = skillGate,
            castSkill = castSkillOut,
            cooldowns = cooldownTracker,
        )
        registrations += services.register(adminSkillCaster)
        registrations += com.astarworks.astera.combat.application.admin.AdminSkillsPanel(
            guard = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.AdminPanelGuard>(),
            registry = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry>(),
            caster = adminSkillCaster,
            // Items enables before Combat (softdepend) but resolve per click anyway.
            testers = { services.get<com.astarworks.astera.core.application.port.outbound.ISkillTesterDelivery>() },
            guiAdapter = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiAdapter>(),
        ).install(services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>())
        // `/astera skill preview`: dummy caster, camera, tick-rate control (Paper only; the simulator has none).
        val previewStage = ports.skillPreviewStage?.invoke(services)
        val tickControl = ports.tickRateControl?.invoke(services)
        val previewService = if (previewStage != null && tickControl != null) {
            com.astarworks.astera.combat.application.preview.SkillPreviewService(
                stage = previewStage,
                tick = tickControl,
                caster = adminSkillCaster,
                poses = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup>(),
                items = services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>(),
                scheduler = scheduler,
                onCastFailed = { viewer, failure -> logger.warning("skill preview cast rejected for $viewer: ${failure.error}") },
            ).also {
                shutdownHooks += { it.shutdown() }
                registrations += services.register(it)
            }
        } else {
            null
        }
        ctx.commandMount("Astera-Combat").register(
            com.astarworks.astera.combat.command.skillCommandSpec(
                adminSkillCaster,
                services.getOrThrow<com.astarworks.astera.core.adapter.api.server.IMcServer>(),
                previewService,
            ),
        )
        // UI-12: `/astera status [id]` lookup and `/astera status codex` (the status codex GUI).
        val statusMessages = services.get<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>()
        val statusPlayers = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerGateway>()
        if (statusMessages != null && statusPlayers != null) {
            val guiRegistry = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiRegistry>()
            ctx.commandMount("Astera-Combat").register(
                com.astarworks.astera.combat.command.statusCommandSpec(
                    codex = com.astarworks.astera.combat.application.service.StatusCodex(statusContents, statusMessages),
                    players = statusPlayers,
                    guiAdapter = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiAdapter>(),
                    server = services.getOrThrow<com.astarworks.astera.core.adapter.api.server.IMcServer>(),
                    codexSpec = { guiRegistry.find(it) },
                ),
            )
        }

        return CombatWiringResult(
            skillGate = skillGate,
            skillCatalog = skillCatalog,
            reloadSkills = { skillEngineContents.reload(skillsContentDir) },
            frameTables = frameTables,
            statusContents = statusContents,
            armorContents = armorContents,
            applyDamage = applyDamageOut,
            applyStatus = applyStatusOut,
            castSkill = castSkillOut,
            trainingRange = trainingService,
            shutdownHooks = shutdownHooks.toList(),
        )
    }
}
