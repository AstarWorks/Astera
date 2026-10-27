package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicStatuses
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * [ISkillGameplayGateway] over the existing combat pipeline (ADR-0301): damage goes through
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
    private val statusContainer: IStatusEffectContainer,
    private val entityEffects: IEntityEffectsGateway,
    private val attributes: IPlayerAttributeContainerStore,
    private val scheduler: IScheduler,
    /** Lazily resolved: the pool service registers after the combat plugin enables. */
    private val resourcePool: () -> IResourcePoolService?,
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

    override fun removeStatus(victim: UUID, statusId: String) {
        val id = runCatching { StatusEffectId(statusId) }.getOrNull() ?: return
        statusContainer.remove(PlayerId(victim), id)
    }

    override fun cleanse(victim: UUID, negativeOnly: Boolean) {
        val target = PlayerId(victim)
        statusContainer.active(target)
            .filter { !negativeOnly || it.spec.kind.harmful }
            .forEach { statusContainer.remove(target, it.spec.id) }
        // Fire and frost count as harmful either way (same as the Cleanse mechanic).
        entityEffects.setFireTicks(target, 0)
        entityEffects.thaw(target)
    }

    override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) {
        entityEffects.setFireTicks(PlayerId(victim), durationTicks.coerceAtLeast(0))
    }

    override fun silence(caster: UUID, victim: UUID, durationTicks: Int) {
        applyStatus(caster, victim, MechanicStatuses.SILENCE_ID.value, 1, durationTicks)
    }

    override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) {
        val key = runCatching { AttributeKey(attribute) }.getOrElse {
            log.warn("skill engine: invalid attribute key '{}'", attribute)
            return
        }
        val (op, value) = when (operation) {
            "add" -> AttributeModifier.Operation.ADD to amount
            // The container multiplies by `value`; the op's amount is the fractional change (0.2 = +20 percent).
            "multiply_base", "multiply_total" -> AttributeModifier.Operation.MULTIPLY to (1.0 + amount)
            else -> {
                log.warn("skill engine: unknown attribute operation '{}'", operation)
                return
            }
        }
        val container = attributes.containerFor(PlayerId(victim))
        val source = "skill-engine:modify_attribute:${UUID.randomUUID()}"
        container.addModifier(AttributeModifier(key, op, value, source))
        if (durationTicks != null && durationTicks > 0) {
            scheduler.run(ExecutionScope.Entity(victim), ticks(durationTicks.toLong())) { container.removeModifiersBySource(source) }
        }
    }

    override fun restoreResource(victim: UUID, resource: String, amount: Double) {
        val type = when (resource.lowercase()) {
            "ammo" -> ResourceType.Ammo
            "energy", "mana", "stamina" -> ResourceType.Energy
            else -> {
                log.warn("skill engine: unknown resource '{}'", resource)
                return
            }
        }
        val pool = resourcePool() ?: return
        val whole = amount.toInt()
        if (whole > 0) pool.applyRegen(PlayerId(victim), type, whole)
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
