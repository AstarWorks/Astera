package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.item.PhaseOverrides
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Wave _skills-2 (ADR-0077) phase-aware skill cast dispatcher.
 *
 * Subscribes to [SkillCast] on the domain event bus, looks up the
 * [SkillSpec], composes the skill's base [PhaseMechanics] with any
 * binding-side [PhaseOverrides] threaded through `MechanicContext.phaseOverrides`,
 * and hands the resulting per-phase mechanic lists to the appropriate
 * archetype handler.
 *
 * ```
 * SkillCastRequested ─→ CastSkillUseCase ─publish→ SkillCast ─subscribe→ SkillCastDispatcher
 *                                                                          │
 *                                                                          ├─ ProjectileHandler
 *                                                                          ├─ MeleeHandler
 *                                                                          ├─ AoeHandler
 *                                                                          ├─ BuffHandler
 *                                                                          ├─ TeleportHandler
 *                                                                          ├─ SequenceHandler
 *                                                                          └─ SummonHandler
 * ```
 *
 * Wiring: each archetype handler holds its own [MechanicDispatcher][
 *   com.astarworks.astera.combat.application.service.MechanicDispatcher]
 * reference, so this class only resolves the spec + composes overrides + picks
 * the handler. PhaseOverrides composition is done here (not inside the
 * handler) so the handler stays archetype-pure and easier to unit-test.
 *
 * Phase 1 still routes `SkillCast` (not `SkillCastRequested`) into here —
 * `CastSkillUseCase` will be extended in this wave to capture the `PhaseOverrides`
 * from `SkillCastRequested` and thread it via a side channel (see
 * [setPendingOverrides]). Phase 6 may replace this with an enriched
 * `SkillCast` event carrying the overrides directly.
 */
public class SkillCastDispatcher(
    private val skillRegistry: ISkillRegistry,
    private val projectileHandler: ProjectileHandler,
    private val meleeHandler: MeleeHandler,
    private val aoeHandler: AoeHandler,
    private val buffHandler: BuffHandler,
    private val teleportHandler: TeleportHandler,
    private val sequenceHandler: SequenceHandler,
    private val summonHandler: SummonHandler,
    private val eventBus: IDomainEventBus,
    private val log: Logger = LoggerFactory.getLogger(SkillCastDispatcher::class.java),
) {

    /**
     * Stash of `(caster, skillId) → PhaseOverrides` set by `CastSkillUseCase`
     * just before it publishes `SkillCast`, consumed once here. Single-entry
     * map per caster (the most recent cast wins — Phase 1 cooldown gating
     * prevents racing casts of the same skill).
     *
     * Phase 6 cleanup: replace with a richer `SkillCast` event variant or a
     * pre-publish enrich step.
     */
    private val pendingOverrides: MutableMap<OverrideKey, PhaseOverrides> = java.util.concurrent.ConcurrentHashMap()

    /** Caster + skill key for the [pendingOverrides] map. */
    public data class OverrideKey(val casterId: com.astarworks.astera.core.domain.player.PlayerId, val skillId: com.astarworks.astera.core.domain.skill.SkillId)

    /** Called by `CastSkillUseCase` just before publishing `SkillCast`. */
    public fun setPendingOverrides(key: OverrideKey, overrides: PhaseOverrides) {
        if (overrides == PhaseOverrides.EMPTY) {
            pendingOverrides.remove(key)
        } else {
            pendingOverrides[key] = overrides
        }
    }

    public fun start(): IDomainEventBus.Subscription =
        eventBus.subscribe(SkillCast::class, EventDelivery.Entity) { event -> handle(event) }

    public fun handle(event: SkillCast): Unit = ExecutionCosts.measure("skill", event.skillId.value) {
        handleCast(event)
    }

    private fun handleCast(event: SkillCast) {
        val spec = skillRegistry.find(event.skillId)
        if (spec == null) {
            log.warn("SkillCastDispatcher: skill {} not found in registry; dropping", event.skillId)
            return
        }

        val overrides = pendingOverrides.remove(OverrideKey(event.caster, event.skillId))
            ?: PhaseOverrides.EMPTY
        val composedPhases = overrides.applyTo(spec.phases)

        try {
            dispatch(spec, composedPhases, event.copy(maxTargets = spec.maxTargets))
        } catch (ex: Exception) {
            log.warn(
                "SkillCastDispatcher: handler for {} (archetype={}) threw; cast dropped",
                spec.id, spec.archetype, ex,
            )
        }
    }

    private fun dispatch(spec: SkillSpec, phases: PhaseMechanics, event: SkillCast) {
        when (spec.archetype) {
            SkillArchetype.PROJECTILE -> {
                val p = requireParams<SkillParams.ProjectileParams>(spec) ?: return
                val ph = requirePhases<PhaseMechanics.Projectile>(spec, phases) ?: return
                projectileHandler.handle(event, p, ph)
            }
            SkillArchetype.MELEE -> {
                val p = requireParams<SkillParams.MeleeParams>(spec) ?: return
                val ph = requirePhases<PhaseMechanics.Melee>(spec, phases) ?: return
                meleeHandler.handle(event, p, ph)
            }
            SkillArchetype.AOE -> {
                val p = requireParams<SkillParams.AoeParams>(spec) ?: return
                val ph = requirePhases<PhaseMechanics.Aoe>(spec, phases) ?: return
                aoeHandler.handle(event, p, ph)
            }
            SkillArchetype.BUFF -> {
                val p = requireParams<SkillParams.BuffParams>(spec) ?: return
                val ph = requirePhases<PhaseMechanics.Buff>(spec, phases) ?: return
                buffHandler.handle(event, p, ph)
            }
            SkillArchetype.TELEPORT -> {
                val p = requireParams<SkillParams.TeleportParams>(spec) ?: return
                val ph = requirePhases<PhaseMechanics.Teleport>(spec, phases) ?: return
                teleportHandler.handle(event, p, ph)
            }
            SkillArchetype.SEQUENCE -> {
                val p = requireParams<SkillParams.SequenceParams>(spec) ?: return
                val ph = requirePhases<PhaseMechanics.Sequence>(spec, phases) ?: return
                sequenceHandler.handle(event, p, ph)
            }
            SkillArchetype.SUMMON -> {
                val p = requireParams<SkillParams.SummonParams>(spec) ?: return
                val ph = requirePhases<PhaseMechanics.Summon>(spec, phases) ?: return
                summonHandler.handle(event, p, ph)
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

    private inline fun <reified P : PhaseMechanics> requirePhases(
        spec: SkillSpec,
        phases: PhaseMechanics,
    ): P? {
        if (phases !is P) {
            log.warn(
                "SkillCastDispatcher: skill {} declares archetype {} but phases={}; " +
                    "YAML→spec validation should have caught this",
                spec.id, spec.archetype, phases::class.simpleName,
            )
            return null
        }
        return phases
    }
}
