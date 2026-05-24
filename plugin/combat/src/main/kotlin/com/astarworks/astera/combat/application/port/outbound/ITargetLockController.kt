package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.targetlock.TargetLockConfig
import com.astarworks.astera.combat.domain.targetlock.TargetLockState
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: ranged target-lock controller (Wave 5combat-depth,
 * ADR-0105).
 *
 * Drives the bow / gun / scope "lock-on" mechanic. The Paper adapter
 * (Wave 6) samples player look-target on a tick scheduler and calls
 * [tickAim] / [breakAim] here; the controller turns continuous aim
 * samples into lock states and publishes `TargetLockAcquired` /
 * `TargetLockReleased` events.
 *
 * # Why session-scoped (no persistence)
 *
 * Same rationale as the other two trackers in this wave: locks are
 * per-engagement tactical state. Per ADR-0105.
 *
 * # Single-target invariant
 *
 * Each player holds at most one lock. Calling [tickAim] with a new
 * target while a lock is already held drops the old lock first
 * (publishing `TargetLockReleased(reason = TargetLost)`).
 */
public interface ITargetLockController {

    /** Replace the tuning config (called once at boot + on reload). */
    public fun configure(config: TargetLockConfig)

    /** Current config. Defaults to [TargetLockConfig.DEFAULT] until [configure]. */
    public fun config(): TargetLockConfig

    /**
     * Record a tick of continuous aim from [playerId] using [weapon]
     * at [target]. The controller:
     *  1. If a different target is already tracked, drops it
     *     (`TargetLockReleased(TargetLost)`) and starts fresh.
     *  2. If weapon changed, drops the current state
     *     (`TargetLockReleased(WeaponSwitched)`).
     *  3. Increments `aimingTicks`; if it crosses
     *     [TargetLockConfig.acquisitionTicks], publishes
     *     `TargetLockAcquired` and arms the bonus.
     */
    public fun tickAim(
        playerId: PlayerId,
        weapon: ItemId,
        target: EntityHandle,
        atTick: Long,
    )

    /**
     * Record an aim-break (no target in the angular envelope this
     * tick). Resets `aimingTicks` to 0 while acquisition is still
     * building; if the lock is already acquired, the state survives
     * until its lifetime expires.
     */
    public fun breakAim(playerId: PlayerId, atTick: Long)

    /**
     * Force-release [playerId]'s lock. Publishes
     * `TargetLockReleased(ManualRelease)`. No-op if no lock is held.
     */
    public fun release(playerId: PlayerId, atTick: Long)

    /**
     * Look up the live lock state for [playerId], or null if none.
     * The controller may prune expired locks in-line using [nowTick].
     */
    public fun stateOf(playerId: PlayerId, nowTick: Long): TargetLockState?

    /**
     * Compute the bonus multiplier to apply on the next ranged hit by
     * [playerId] against [target] at [nowTick]. Returns `1.0` when no
     * active lock targets [target]. Single-use: after this returns
     * `> 1.0`, the bonus disarms (the [target] still stays locked
     * until lifetime expires, but the bonus only fires on the first
     * ranged hit after acquisition).
     */
    public fun consumeBonusFor(
        playerId: PlayerId,
        target: EntityHandle,
        nowTick: Long,
    ): Double
}
