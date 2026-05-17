package com.astarworks.astera.combat.platform

import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.application.service.MutableSkillRegistry
import com.astarworks.astera.combat.application.service.MutableStatusRegistry
import com.astarworks.astera.combat.application.service.SkillLoaderService
import com.astarworks.astera.combat.application.service.StatusEffectLoaderService
import com.astarworks.astera.combat.application.service.YamlElementMatrix
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.application.usecase.status.TickCooldownUseCase
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Combat plugin entrypoint (ADR-0022 Phase 4).
 *
 * Resolves shared services from Astera-Core, builds combat-owned in-memory
 * registries (skills, status effects, element matrix), and publishes them on
 * the shared [ServiceRegistry] so downstream plugins (Match / Stats) can
 * subscribe. Use cases (`ApplyDamage`, `CastSkill`, `ApplyStatus`,
 * `TickCooldown`) and the Paper-side [com.astarworks.astera.combat.application.port.outbound.IDamageGateway]
 * + [com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer]
 * implementations land in Stage 3-改 / Phase 6 — Phase 4 publishes the
 * registries only so the rest of the plugin tree can resolve them.
 *
 * Lifecycle: Bukkit guarantees Astera-Core finishes `onEnable` before this
 * plugin's `onEnable` runs via the `depend: [Astera-Core]` entry in
 * plugin.yml. If that contract somehow breaks the plugin disables itself
 * rather than NPE on missing services.
 */
public class AsteraCombatPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()

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
        val skillRegistry = MutableSkillRegistry()
        val skillReport = SkillLoaderService(skillRegistry)
            .loadFrom(core.contentDir.resolve("skills"))
        logger.info("Skills loaded: ${skillReport.loaded} ok, ${skillReport.failed} failed")

        val statusRegistry = MutableStatusRegistry()
        val statusReport = StatusEffectLoaderService(statusRegistry)
            .loadFrom(core.contentDir.resolve("status"))
        logger.info("Status effects loaded: ${statusReport.loaded} ok, ${statusReport.failed} failed")

        val elementMatrix: IElementMatrix = YamlElementMatrix.loadFromFile(
            core.contentDir.resolve("balance").resolve("element-matrix.yaml"),
        )

        // 3. Register Combat ports onto the shared registry --------------------
        // IDamageGateway / IStatusEffectContainer impls (Paper-side) land in
        // Stage 7-改 — Phase 4 publishes the read-only registries only.
        registrations += services.register<ISkillRegistry>(skillRegistry)
        registrations += services.register<IStatusEffectRegistry>(statusRegistry)
        registrations += services.register<IElementMatrix>(elementMatrix)

        // 4. Wire use cases if runtime impls are present -----------------------
        // IDomainEventBus and IScheduler are always present (Core publishes them).
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val scheduler = services.getOrThrow<IScheduler>()

        // ICooldownTracker is registered by Astera-Weapons. Gracefully degrade if absent.
        val cooldownTracker = services.get<ICooldownTracker>()
        if (cooldownTracker == null) {
            logger.warning(
                "ICooldownTracker not present — Astera-Weapons may not be loaded. " +
                    "CastSkillUseCase and TickCooldownUseCase will not be wired.",
            )
        }

        val damageGateway = services.get<IDamageGateway>()
        val statusContainer = services.get<IStatusEffectContainer>()

        if (damageGateway != null && statusContainer != null && cooldownTracker != null) {
            val applyDamage = ApplyDamageUseCase(
                damageGateway = damageGateway,
                statusContainer = statusContainer,
                elementMatrix = elementMatrix,
                eventBus = eventBus,
            )
            val castSkill = CastSkillUseCase(
                skillRegistry = skillRegistry,
                statusContainer = statusContainer,
                eventBus = eventBus,
                cooldownTracker = cooldownTracker,
                scheduler = scheduler,
            )
            val applyStatus = ApplyStatusUseCase(
                effectRegistry = statusRegistry,
                effectContainer = statusContainer,
                eventBus = eventBus,
            )
            @Suppress("UNUSED_VARIABLE")
            val tickCooldown = TickCooldownUseCase(
                cooldownTracker = cooldownTracker,
                scheduler = scheduler,
            )

            logger.info(
                "Astera-Combat use cases wired: " +
                    "ApplyDamage, CastSkill, ApplyStatus, TickCooldown",
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
                "ISkillRegistry, IStatusEffectRegistry, IElementMatrix",
        )
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Combat disabled.")
    }
}
