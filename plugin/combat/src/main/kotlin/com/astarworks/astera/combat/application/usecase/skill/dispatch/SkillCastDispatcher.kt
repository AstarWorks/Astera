package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.weapons.application.port.outbound.IProjectileService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Wave 5a primary subscriber for [SkillCast].
 *
 * Before this class existed, [SkillCast] had zero subscribers (the
 * "hollow PoC" status flagged in `RTM-PARITY-TRACKER.md`) — `CastSkillUseCase`
 * would publish the event and nothing happened. The dispatcher closes that
 * loop:
 *
 * ```
 * CastSkillUseCase ─publish→ SkillCast ─subscribe→ SkillCastDispatcher
 *                                                     │
 *                                                     ├─ ProjectileHandler   (PROJECTILE)
 *                                                     ├─ MeleeHandler        (MELEE)
 *                                                     ├─ AoeHandler          (AOE)
 *                                                     ├─ BuffHandler         (BUFF)
 *                                                     ├─ TeleportHandler     (TELEPORT)
 *                                                     └─ SummonHandler       (SUMMON, stub)
 * ```
 *
 * Wiring is done in `AsteraCombatPlugin.onEnable` (Phase C) after the
 * required ports — `IProjectileService` (weapons), `INearbyTargetQuery`,
 * `IWorldGateway` — resolve from the shared `ServiceRegistry`. Skipped
 * gracefully when any required port is missing (e.g. Astera-Weapons not
 * loaded → no PROJECTILE dispatch, but the rest still works).
 *
 * Exception handling: a handler throwing must not poison the subscription.
 * Each dispatch is wrapped in a try/catch that logs at WARN and continues —
 * a buggy handler kills its own cast, not the whole bus.
 */
public class SkillCastDispatcher(
    private val skillRegistry: ISkillRegistry,
    applyDamage: ApplyDamageUseCase,
    applyStatus: ApplyStatusUseCase,
    worldGateway: IWorldGateway,
    projectileService: IProjectileService,
    nearbyQuery: INearbyTargetQuery,
    private val eventBus: IDomainEventBus,
    private val log: Logger = LoggerFactory.getLogger(SkillCastDispatcher::class.java),
) {
    private val projectileHandler = ProjectileHandler(projectileService)
    private val meleeHandler = MeleeHandler(nearbyQuery, applyDamage)
    private val aoeHandler = AoeHandler(nearbyQuery, applyDamage)
    private val buffHandler = BuffHandler(applyStatus)
    private val teleportHandler = TeleportHandler(worldGateway)
    private val summonHandler = SummonHandler()

    /**
     * Subscribe to [SkillCast] on the shared event bus and return the
     * [IDomainEventBus.Subscription] so the caller (Phase C wiring) can
     * cancel on plugin disable.
     */
    public fun start(): IDomainEventBus.Subscription =
        eventBus.subscribe(SkillCast::class) { event -> handle(event) }

    /**
     * Resolve the spec, validate archetype × params pairing, and dispatch.
     * Exposed (not private) for direct unit-test invocation without rebinding
     * the event bus.
     */
    public fun handle(event: SkillCast) {
        val spec = skillRegistry.find(event.skillId)
        if (spec == null) {
            log.warn("SkillCastDispatcher: skill {} not found in registry; dropping", event.skillId)
            return
        }
        try {
            dispatch(spec, event)
        } catch (ex: Exception) {
            log.warn(
                "SkillCastDispatcher: handler for {} (archetype={}) threw; cast dropped",
                spec.id, spec.archetype, ex,
            )
        }
    }

    private fun dispatch(spec: SkillSpec, event: SkillCast) {
        when (spec.archetype) {
            SkillArchetype.PROJECTILE -> {
                val params = requireParams<SkillParams.ProjectileParams>(spec) ?: return
                projectileHandler.handle(event, params)
            }
            SkillArchetype.MELEE -> {
                val params = requireParams<SkillParams.MeleeParams>(spec) ?: return
                meleeHandler.handle(event, params)
            }
            SkillArchetype.AOE -> {
                val params = requireParams<SkillParams.AoeParams>(spec) ?: return
                aoeHandler.handle(event, params)
            }
            SkillArchetype.BUFF -> {
                val params = requireParams<SkillParams.BuffParams>(spec) ?: return
                buffHandler.handle(event, params)
            }
            SkillArchetype.TELEPORT -> {
                val params = requireParams<SkillParams.TeleportParams>(spec) ?: return
                teleportHandler.handle(event, params)
            }
            SkillArchetype.SUMMON -> {
                val params = requireParams<SkillParams.SummonParams>(spec) ?: return
                summonHandler.handle(event, params)
            }
        }
    }

    private inline fun <reified P : SkillParams> requireParams(spec: SkillSpec): P? {
        val params = spec.params
        if (params !is P) {
            log.warn(
                "SkillCastDispatcher: skill {} declares archetype {} but params={}; " +
                    "YAML→spec validation should have caught this",
                spec.id, spec.archetype, params::class.simpleName,
            )
            return null
        }
        return params
    }
}
