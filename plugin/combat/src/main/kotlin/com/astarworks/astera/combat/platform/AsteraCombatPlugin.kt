package com.astarworks.astera.combat.platform

import com.astarworks.astera.combat.platform.paper.CombatPaperPlatform
import com.astarworks.astera.combat.platform.wiring.CombatWiring
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.platform.wiring.WiringContext
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

    private var wiringContext: WiringContext? = null
    private var wiringResult: CombatWiringResult? = null
    private var paperPlatform: CombatPaperPlatform? = null

    override fun onEnable() {
        logger.info("Astera-Combat enabling…")

        // 1. Resolve Astera-Core ----------------------------------------------
        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Combat.")
                server.pluginManager.disablePlugin(this); return
            }

        // 2. Host-neutral wiring (ADR-0174) + Paper-only binding ----------------
        val ctx = WiringContext(core.services, core.contentDir, logger)
        wiringContext = ctx
        val platform = CombatPaperPlatform(this)
        paperPlatform = platform
        val wired = CombatWiring.install(ctx, platform.ports)
        wiringResult = wired
        platform.bind(ctx, wired)

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
        // Wave 5match-hud / 6a-N2: stop scheduler-driven pieces (cooldown HUD
        // poll, per-tick cooldown expiry, status tick sweep, projectile
        // watchdog) before the subscriptions / registrations are closed.
        wiringResult?.shutdown()
        wiringResult = null
        paperPlatform?.teardown()
        paperPlatform = null
        wiringContext?.close()
        wiringContext = null
        logger.info("Astera-Combat disabled.")
    }
}
