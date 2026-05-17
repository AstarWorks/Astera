package com.astarworks.astera.combat.application.usecase.skill

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Gates a skill cast: looks up the spec, checks silence and per-skill cooldown,
 * records the cooldown via [ICooldownTracker], publishes [SkillCast], and
 * returns the spec.
 *
 * Cooldowns are now tracked by [ICooldownTracker] (core port) keyed on
 * [CooldownKey.Skill]. The old in-module [ConcurrentHashMap] was removed as
 * part of R0.c (ADR-0022 Phase 3 cleanup).
 *
 * Downstream skill effects (ApplyStatus, ApplyDamage) are triggered by
 * runtime mechanics that subscribe to [SkillCast] — they are not invoked here.
 */
public class CastSkillUseCase(
    private val skillRegistry: ISkillRegistry,
    private val statusContainer: IStatusEffectContainer,
    private val eventBus: IDomainEventBus,
    private val cooldownTracker: ICooldownTracker,
    private val scheduler: IScheduler,
) {
    public fun execute(
        casterId: PlayerId,
        skillId: SkillId,
        at: Vec3 = Vec3.ZERO,
    ): Result<SkillSpec, CastSkillError> {
        // 1. Look up spec
        val spec = skillRegistry.find(skillId)
            ?: return Result.failure(CastSkillError.SkillNotFound(skillId))

        // 2. Silence check
        if (statusContainer.has(casterId, SILENCE_ID)) {
            return Result.failure(CastSkillError.Silenced(casterId))
        }

        // 3. Cooldown check via ICooldownTracker
        val key = CooldownKey.Skill(skillId.value)
        if (cooldownTracker.isCoolingDown(casterId, key)) {
            val remainingTicks = cooldownTracker.remainingTicks(casterId, key)
            val remainingMs = remainingTicks * TICKS_TO_MS
            return Result.failure(CastSkillError.OnCooldown(skillId, remainingMs))
        }

        // 4. Record cooldown
        if (spec.cooldownTicks > 0) {
            cooldownTracker.start(casterId, key, spec.cooldownTicks.toLong())
        }

        // 5. Publish event
        val atTick = scheduler.currentTick()
        eventBus.publish(SkillCast(caster = casterId, skillId = skillId, at = at, atTick = atTick))

        return Result.success(spec)
    }

    private companion object {
        val SILENCE_ID = StatusEffectId("silence")
        const val TICKS_TO_MS: Long = 50L // 20 tps
    }
}

/** Expected failure modes of [CastSkillUseCase.execute]. */
public sealed class CastSkillError {
    public data class SkillNotFound(val skillId: SkillId) : CastSkillError()
    public data class OnCooldown(val skillId: SkillId, val remainingMs: Long) : CastSkillError()
    public data class Silenced(val casterId: PlayerId) : CastSkillError()
}
