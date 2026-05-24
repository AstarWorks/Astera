package com.astarworks.astera.combat.domain.targetlock

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Live target-lock state for one player (Wave 5combat-depth, ADR-0105).
 *
 * Each player can hold at most one active lock (single-target rule, see
 * [TargetLockConfig] KDoc). Switching weapons or scoping a different
 * entity drops the previous state — the controller is responsible for
 * publishing a [com.astarworks.astera.combat.domain.event.TargetLockReleased]
 * event on drop.
 *
 * # Acquisition vs locked
 *
 * Acquisition is the **build-up** before lock fires: the player has
 * aimed at the target for [aimingTicks] continuous ticks but
 * `aimingTicks < TargetLockConfig.acquisitionTicks`. Once the threshold
 * is crossed, [lockedSinceTick] becomes non-null and the next ranged
 * hit applies the damage bonus.
 *
 * @property playerId Player holding the lock / aiming.
 * @property weaponId Ranged weapon used. Switching weapon resets state.
 * @property target Entity being aimed at. Vendor-neutral handle so
 *   mobs / players / NPCs are all candidates.
 * @property aimingTicks Number of continuous ticks the player has been
 *   aiming at [target]. Resets when aim breaks the angular threshold
 *   for one tick.
 * @property lockedSinceTick Server tick at which the lock acquired, or
 *   null while still in acquisition. Non-null = "locked now".
 * @property lastAimAtTick Server tick of the most-recent aim sample.
 *   Used by lifetime decay.
 */
public data class TargetLockState(
    public val playerId: PlayerId,
    public val weaponId: ItemId,
    public val target: EntityHandle,
    public val aimingTicks: Int,
    public val lockedSinceTick: Long?,
    public val lastAimAtTick: Long,
) {
    init {
        require(aimingTicks >= 0) {
            "TargetLockState.aimingTicks must be >= 0: $aimingTicks"
        }
        require(lastAimAtTick >= 0L) {
            "TargetLockState.lastAimAtTick must be >= 0: $lastAimAtTick"
        }
        lockedSinceTick?.let { since ->
            require(since >= 0L) {
                "TargetLockState.lockedSinceTick must be >= 0 when set: $since"
            }
            require(since <= lastAimAtTick) {
                "TargetLockState.lockedSinceTick ($since) must be <= lastAimAtTick ($lastAimAtTick)"
            }
        }
    }

    /** True iff the lock has acquired (not still in build-up). */
    public val isLocked: Boolean get() = lockedSinceTick != null

    /**
     * True iff the lock has expired under [config] at [nowTick]. Only
     * meaningful when [isLocked] — acquisition decay is the controller's
     * concern (a single missed-aim tick resets [aimingTicks]).
     */
    public fun isLockExpiredAt(config: TargetLockConfig, nowTick: Long): Boolean {
        val since = lockedSinceTick ?: return false
        return nowTick - since > config.lockLifetimeTicks
    }
}
