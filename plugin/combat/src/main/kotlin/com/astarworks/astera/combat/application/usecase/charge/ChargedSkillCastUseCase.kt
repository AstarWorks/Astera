package com.astarworks.astera.combat.application.usecase.charge

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.service.ChargeTracker
import com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase
import com.astarworks.astera.combat.domain.event.SkillChargeCancelled
import com.astarworks.astera.combat.domain.event.SkillChargeStarted
import com.astarworks.astera.combat.domain.skill.ChargedSkillVariant
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Orchestrates the lifecycle of a hold-to-cast (`ChargedSkillVariant.Charged`)
 * skill: `startCharge` → (`releaseCharge` | `cancelCharge` | timer-fire) →
 * (cast | abort).
 *
 * Delegates the actual "fire the skill" effect to [CastSkillUseCase] so
 * cooldown / silence / energy / event-publish concerns stay in one place. This
 * use case only adds the *charge phase* (timer + intermediate events) on top
 * of the instant-cast pipeline.
 *
 * Lifecycle:
 *
 *  - [startCharge] looks up the spec, asserts it is `Charged(ticks)`,
 *    schedules the eventual release through [ChargeTracker], and publishes
 *    [SkillChargeStarted]. Starting a new charge while another is in flight
 *    silently displaces the previous one (no `SkillChargeCancelled` is
 *    published in that case — the player chose to retarget).
 *  - [releaseCharge] is the player-initiated early release. Cancels the
 *    pending timer and immediately invokes [CastSkillUseCase.execute].
 *  - [cancelCharge] is the abort path (interrupt, stun, target lost).
 *    Cancels the pending timer and publishes [SkillChargeCancelled]; no
 *    skill cast is performed.
 *  - On natural completion the [ChargeTracker] callback hits
 *    [releaseInternal], which calls into [CastSkillUseCase.execute] just
 *    like [releaseCharge].
 *
 * The `at` location for [CastSkillUseCase.execute] is `Vec3.ZERO` in Wave 5a
 * — the projectile / aoe engines that consume `SkillCast.at` are not yet
 * wired (Phase 2 mid Batch 3); a later wave will inject a positional
 * resolver here.
 */
public class ChargedSkillCastUseCase(
    private val castSkill: CastSkillUseCase,
    private val chargeTracker: ChargeTracker,
    private val eventBus: IDomainEventBus,
    private val skillRegistry: ISkillRegistry,
    private val scheduler: IScheduler,
) {

    /**
     * Begin a charge window for [skillId] cast by [casterId].
     *
     * Returns the `Charged` variant on success so callers can read its
     * `chargeTicks` for HUD rendering without consulting the registry
     * themselves. Failures:
     *  - [ChargedSkillError.UnknownSkill] — no spec registered.
     *  - [ChargedSkillError.NotAChargedSkill] — spec exists but is `Instant`.
     */
    public fun startCharge(
        casterId: PlayerId,
        skillId: SkillId,
    ): Result<ChargedSkillVariant.Charged, ChargedSkillError> {
        val spec = skillRegistry.find(skillId)
            ?: return Result.failure(ChargedSkillError.UnknownSkill(skillId))

        val variant = spec.chargeVariant
        if (variant !is ChargedSkillVariant.Charged) {
            return Result.failure(ChargedSkillError.NotAChargedSkill(skillId))
        }

        chargeTracker.start(casterId, skillId, variant.chargeTicks) {
            releaseInternal(casterId, skillId)
        }
        eventBus.publish(
            SkillChargeStarted(
                caster = casterId,
                skillId = skillId,
                chargeTicks = variant.chargeTicks,
                atTick = scheduler.currentTick(),
            ),
        )

        return Result.success(variant)
    }

    /**
     * Player-initiated early release: fires the skill immediately and stops
     * the pending timer.
     *
     * Returns [ChargedSkillError.NoActiveCharge] if no charge is in flight
     * for the player.
     */
    public fun releaseCharge(casterId: PlayerId): Result<Unit, ChargedSkillError> {
        val state = chargeTracker.release(casterId)
            ?: return Result.failure(ChargedSkillError.NoActiveCharge(casterId))
        // Delegate to the instant-cast pipeline. We ignore the inner Result
        // here — the outer call's responsibility is to confirm "we asked
        // CastSkill to fire"; downstream errors (e.g. cooldown raced in
        // between) surface via the inner CastSkillError path which the caller
        // can recover from a separate channel if desired.
        castSkill.execute(state.playerId, state.skillId, at = Vec3.ZERO)
        return Result.success(Unit)
    }

    /**
     * Abort the in-flight charge without firing the skill.
     *
     * Publishes [SkillChargeCancelled] with the elapsed-tick count so HUD /
     * audit listeners can reflect the interrupt. Returns
     * [ChargedSkillError.NoActiveCharge] if no charge is in flight.
     */
    public fun cancelCharge(casterId: PlayerId): Result<Unit, ChargedSkillError> {
        val state = chargeTracker.cancel(casterId)
            ?: return Result.failure(ChargedSkillError.NoActiveCharge(casterId))
        val elapsed = (scheduler.currentTick() - state.startedAtTick).coerceAtLeast(0L)
        eventBus.publish(
            SkillChargeCancelled(
                caster = state.playerId,
                skillId = state.skillId,
                elapsedTicks = elapsed,
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Scheduler-driven natural completion path. Invoked from the
     * [ChargeTracker] callback after `chargeTicks` elapse with no manual
     * release / cancel. Behaviorally identical to [releaseCharge] minus the
     * "manually release the tracker entry" step (the tracker has already
     * removed it).
     */
    private fun releaseInternal(casterId: PlayerId, skillId: SkillId) {
        castSkill.execute(casterId, skillId, at = Vec3.ZERO)
    }
}
