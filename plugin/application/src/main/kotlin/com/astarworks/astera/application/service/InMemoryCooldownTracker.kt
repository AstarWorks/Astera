package com.astarworks.astera.application.service

import com.astarworks.astera.application.port.outbound.ICooldownTracker
import com.astarworks.astera.application.port.outbound.IScheduler
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ICooldownTracker]. Cooldowns live for the lifetime of the JVM —
 * a server restart clears all cooldowns, which is fine for Phase 2 (no
 * persistent skill timers in the planned game modes yet).
 *
 * Reads scheduler.currentTick() lazily so changing the tick source between
 * Paper / Folia doesn't ripple here.
 */
class InMemoryCooldownTracker(private val scheduler: IScheduler) : ICooldownTracker {

    private data class Key(val playerId: PlayerId, val weaponId: WeaponId)

    /** Value = absolute tick at which the cooldown expires. */
    private val expiresAt = ConcurrentHashMap<Key, Long>()

    override fun isOnCooldown(playerId: PlayerId, weaponId: WeaponId): Boolean =
        remainingTicks(playerId, weaponId) > 0

    override fun remainingTicks(playerId: PlayerId, weaponId: WeaponId): Long {
        val expires = expiresAt[Key(playerId, weaponId)] ?: return 0
        return (expires - scheduler.currentTick()).coerceAtLeast(0)
    }

    override fun start(playerId: PlayerId, weaponId: WeaponId, durationTicks: Int) {
        require(durationTicks >= 0) { "durationTicks must be >= 0: $durationTicks" }
        expiresAt[Key(playerId, weaponId)] = scheduler.currentTick() + durationTicks
    }
}
