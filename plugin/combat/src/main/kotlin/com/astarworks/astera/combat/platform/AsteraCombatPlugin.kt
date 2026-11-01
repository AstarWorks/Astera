package com.astarworks.astera.combat.platform

import com.astarworks.astera.core.platform.paper.listeners
import com.astarworks.astera.combat.platform.paper.CombatPaperPlatform
import com.astarworks.astera.combat.platform.wiring.CombatWiring
import com.astarworks.astera.combat.platform.wiring.CombatLifecycle
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.RewireCoordinator
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Combat plugin entrypoint (ADR-0022 Phase 4, expanded for Wave 5a).
 *
 * Wave 5a additions wired here:
 *  - `IArmorRegistry` + `ArmorLoaderService` (publishes registry, loads YAML)
 *  - `IDamageRandomizer` (`KotlinRandomDamageRandomizer` default; tests bind `FakeDamageRandomizer`)
 *  - `IKnockbackGateway` (`NoOpKnockbackGateway` default; Wave 6 `PaperKnockbackGateway` overrides)
 *  - `IArmorEquipmentProvider` (`StubArmorEquipmentProvider` default; Wave 6 reads inventory)
 *  - `INearbyTargetQuery` (`EmptyNearbyTargetQuery` default; Wave 6 `PaperNearbyTargetQuery`)
 *  - `DamageRule` instance with full modifier pipeline
 *    (Headshot → ArmorReduction → CriticalStrike → Knockback) registered on
 *    the ServiceRegistry so Wave 6 adapters can resolve it
 *  - `CastSkillUseCase` spends ammo / energy from Core's `IResourcePoolService` (WP2)
 *  - `SkillCastDispatcher` subscribed to `SkillCast` with 6 archetype handlers
 *    (PROJECTILE/MELEE/AOE/BUFF/TELEPORT/SUMMON; SUMMON stubbed for Wave 6)
 *
 * Wave 6 will replace the stub/default impls (`StubArmorEquipmentProvider`,
 * `NoOpKnockbackGateway`, `EmptyNearbyTargetQuery`) with Paper-backed
 * versions registered ahead of this plugin's wiring step.
 */
public class AsteraCombatPlugin : JavaPlugin() {

    private var coordinator: RewireCoordinator? = null

    override fun onEnable() {
        logger.info("Astera-Combat enabling…")

        // 1. Resolve Astera-Core ----------------------------------------------
        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Combat.")
                server.pluginManager.disablePlugin(this); return
            }

        // 2. Rewirable generation (ADR-0311): host-neutral wiring + Paper-only binding, one platform per generation.
        coordinator = core.rewire
        core.rewire.register(
            CombatLifecycle(
                install = { ctx ->
                    val platform = CombatPaperPlatform(this, ctx.listeners(this))
                    ctx.attachments[PLATFORM_KEY] = platform
                    CombatWiring.install(ctx, platform.ports)
                },
                bindHost = { ctx, wired -> (ctx.attachments[PLATFORM_KEY] as CombatPaperPlatform).bind(ctx, wired) },
                unbindHost = { ctx -> (ctx.attachments.remove(PLATFORM_KEY) as? CombatPaperPlatform)?.teardown() },
            ),
        )
        val up = core.rewire.up(CombatLifecycle.OWNER)
        if (up is Result.Failure) {
            logger.severe("Astera-Combat failed to wire: ${up.error}")
            server.pluginManager.disablePlugin(this); return
        }

        logger.info(
            "Astera-Combat enabled. Services published: " +
                "ISkillRegistry, IStatusEffectRegistry, IElementMatrix, " +
                "IArmorRegistry, IDamageRandomizer, " +
                "IDamageHistoryService, DamageRule, " +
                "IExplosionGateway, IAreaEffectGateway, ILightningGateway, " +
                "IFireSpreadController (Wave 5m / ADR-0088), " +
                "IComboTracker, IFocusFireTracker, ITargetLockController " +
                "(Wave 5combat-depth / ADR-0105)",
        )
    }

    override fun onDisable() {
        coordinator?.down(CombatLifecycle.OWNER)
        coordinator = null
        logger.info("Astera-Combat disabled.")
    }

    private companion object {
        const val PLATFORM_KEY = "combat.platform"
    }
}
