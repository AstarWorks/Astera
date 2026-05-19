package com.astarworks.astera.combat.application.usecase.skill

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.usecase.cost.ConsumeManaUseCase
import com.astarworks.astera.combat.domain.event.ManaConsumptionSource
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Gates a skill cast: looks up the spec, checks silence and per-skill cooldown,
 * **debits mana (Wave 5a)** if the spec carries a cost, records the cooldown
 * via [ICooldownTracker], publishes [SkillCast], and returns the spec.
 *
 * Mana is debited *after* silence/cooldown gates but *before* the cooldown is
 * recorded and the [SkillCast] event fires. This ordering means: an
 * `InsufficientMana` failure leaves the cooldown untouched (player can retry
 * once mana regens) and no `SkillCast` is published, so downstream mechanics
 * (status, damage) don't trigger off a failed cast. If mana debit succeeds,
 * the cast is committed: cooldown starts and the event ships.
 *
 * [consumeMana] is nullable for backward-compat with skills/specs that don't
 * declare `manaCost` and for tests that don't care about mana. When `null`
 * or when `spec.manaCost == null`, the mana step is skipped entirely.
 *
 * Cooldowns are tracked by [ICooldownTracker] (core port) keyed on
 * [CooldownKey.Skill]. The old in-module [java.util.concurrent.ConcurrentHashMap]
 * was removed as part of R0.c (ADR-0022 Phase 3 cleanup).
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
    private val consumeMana: ConsumeManaUseCase? = null,
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

        // 4. Mana cost (Wave 5a)
        val manaCost = spec.manaCost
        if (manaCost != null && consumeMana != null) {
            val outcome = consumeMana.execute(
                playerId = casterId,
                source = ManaConsumptionSource.Skill(skillId),
                amount = manaCost,
            )
            if (outcome is Result.Failure) {
                val mappedError = when (val err = outcome.error) {
                    is ManaError.Insufficient -> CastSkillError.InsufficientMana(
                        have = err.have,
                        tried = err.tried,
                    )
                    is ManaError.PlayerNotInitialized -> CastSkillError.InsufficientMana(
                        have = 0L,
                        tried = manaCost,
                    )
                }
                return Result.failure(mappedError)
            }
        }

        // 5. Record cooldown
        if (spec.cooldownTicks > 0) {
            cooldownTracker.start(casterId, key, spec.cooldownTicks.toLong())
        }

        // 6. Publish event
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

    /**
     * The caster did not have enough mana to pay [tried]. [have] is the
     * current pool balance (`0` when the player's pool was never initialized).
     * Wave 5a.
     */
    public data class InsufficientMana(val have: Long, val tried: Long) : CastSkillError()
}
