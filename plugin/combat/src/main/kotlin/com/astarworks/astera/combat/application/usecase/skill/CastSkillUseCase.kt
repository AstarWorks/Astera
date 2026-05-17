package com.astarworks.astera.combat.application.usecase.skill

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Gates a skill cast: looks up the spec, checks silence and per-skill cooldown,
 * records the cooldown, publishes [SkillCast], and returns the spec.
 *
 * NOTE on [ICooldownTracker] (weapons plugin): the Weapons tracker is keyed on
 * [com.astarworks.astera.weapons.domain.weapon.WeaponId]. Skill-level cooldowns
 * are a distinct granularity and are tracked here in an in-memory map for Stage
 * 3-改. A dedicated ISkillCooldownTracker port will be introduced in Stage 7-改
 * when the Paper layer is wired.
 *
 * Downstream skill effects (ApplyStatus, ApplyDamage) are triggered by runtime
 * mechanics that subscribe to [SkillCast] — they are not invoked here.
 */
public class CastSkillUseCase(
    private val skillRegistry: ISkillRegistry,
    private val statusContainer: IStatusEffectContainer,
    private val eventBus: IDomainEventBus,
) {
    /**
     * In-memory per-(player, skill) cooldown state.
     * Key: Pair<PlayerId, SkillId> → expiry epoch-ms.
     */
    private val cooldowns: MutableMap<Pair<PlayerId, SkillId>, Long> =
        ConcurrentHashMap()

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

        // 3. Cooldown check (in-module tracker)
        val now = System.currentTimeMillis()
        val key = Pair(casterId, skillId)
        val expiry = cooldowns[key]
        if (expiry != null && now < expiry) {
            val remainingMs = expiry - now
            return Result.failure(CastSkillError.OnCooldown(skillId, remainingMs))
        }

        // 4. Record cooldown (ticks → ms at 20 tps)
        val cooldownMs = spec.cooldownTicks * TICKS_TO_MS
        if (cooldownMs > 0) {
            cooldowns[key] = now + cooldownMs
        }

        // 5. Publish event
        val atTick = now / TICKS_TO_MS // approximate game tick
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
