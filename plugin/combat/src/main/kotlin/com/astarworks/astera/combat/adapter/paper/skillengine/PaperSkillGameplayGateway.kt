package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * [ISkillGameplayGateway] over the existing combat pipeline (ADR-0299): damage goes through
 * [ApplyDamageUseCase] (armor, element matrix, i-frames, kill events), statuses through
 * [ApplyStatusUseCase], impulses through [IKnockbackGateway] (knockback resistance honoured) and
 * hit-stop through the dispatcher's own hold clock, supplied as [holdEntity].
 *
 * Main thread only.
 */
public class PaperSkillGameplayGateway(
    private val applyDamage: ApplyDamageUseCase,
    private val applyStatus: ApplyStatusUseCase,
    private val knockback: IKnockbackGateway,
    private val casterPosition: (UUID) -> Vec3?,
    /** MechanicDispatcher's hit-stop clock: zero velocity + freeze, deferred same-tick movement. */
    private val holdEntity: (PlayerId, Int) -> Unit,
) : ISkillGameplayGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3) {
        if (components.isEmpty()) return
        val profile = DamageProfile(
            components.map { DamageComponent(base = NumericValue.Literal(it.amount), attribute = attributeOf(it.kind)) },
        )
        applyDamage.execute(
            DamageAttempt(
                targetId = PlayerId(victim),
                baseProfile = profile,
                source = DamageSource.Skill(
                    skillId = ENGINE_SKILL_ID,
                    casterId = PlayerId(caster),
                    casterPosition = casterPosition(caster) ?: hitPoint,
                ),
            ),
        )
    }

    override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {
        val id = runCatching { StatusEffectId(statusId) }.getOrElse {
            log.warn("skill engine: invalid status id '{}'", statusId)
            return
        }
        val result = applyStatus.execute(
            targetId = PlayerId(victim),
            effectId = id,
            durationTicks = durationTicks.takeIf { it > 0 },
            sourceId = PlayerId(caster),
            stacks = stacks.coerceAtLeast(1),
        )
        if (result is Result.Failure) log.warn("skill engine: status '{}' not applied: {}", statusId, result.error)
    }

    override fun impulse(victim: UUID, vector: Vec3) {
        knockback.applyImpulse(PlayerId(victim), vector)
    }

    override fun freezeEntities(entities: List<UUID>, ticks: Int) {
        if (ticks <= 0) return
        for (id in entities.distinct()) holdEntity(PlayerId(id), ticks)
    }

    private fun attributeOf(kind: String): DamageAttribute =
        DamageAttribute.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: DamageAttribute.PHYSICAL

    private companion object {
        val ENGINE_SKILL_ID = SkillId("skill-engine")
    }
}
