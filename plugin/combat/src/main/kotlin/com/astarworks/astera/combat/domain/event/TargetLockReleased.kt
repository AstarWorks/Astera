package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ITargetLockController` when a previously-acquired lock
 * ends — lifetime expiry, weapon switch, target out of range, or
 * explicit release (Wave 5combat-depth, ADR-0105).
 *
 * Pair event to [TargetLockAcquired]. Subscribers can rely on every
 * `Acquired` being followed by exactly one `Released` (unless the
 * server shuts down mid-lock).
 *
 * @property reason Why the lock ended. Sealed so reason-specific
 *   reactions (HUD vs. logging) are exhaustive.
 */
public data class TargetLockReleased(
    public val playerId: PlayerId,
    public val weaponId: ItemId,
    public val target: EntityHandle,
    public val reason: Reason,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    init {
        require(atTick >= 0L) {
            "TargetLockReleased.atTick must be >= 0: $atTick"
        }
    }

    public sealed interface Reason {
        /** Lock lifetime ([com.astarworks.astera.combat.domain.targetlock.TargetLockConfig.lockLifetimeTicks]) elapsed without re-aim. */
        public data object Expired : Reason

        /** Player swapped to a different weapon (or sheathed). */
        public data object WeaponSwitched : Reason

        /** Target moved out of distance / angle envelope. */
        public data object TargetLost : Reason

        /** Caller (admin command, gameplay system) released explicitly. */
        public data object ManualRelease : Reason
    }
}
