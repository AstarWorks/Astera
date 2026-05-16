package com.astarworks.astera.weapons.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.weapon.WeaponId

/**
 * Per-(player, weapon) cooldown tracking.
 *
 * Phase 2 starter granularity: cooldowns key off `WeaponId`. Phase 2 mid
 * adds skill-level granularity (separate cooldowns per trigger / per skill).
 */
interface ICooldownTracker {

    /** True iff the player still has time remaining on this weapon. */
    fun isOnCooldown(playerId: PlayerId, weaponId: WeaponId): Boolean

    /** Remaining cooldown in ticks; 0 when not on cooldown. */
    fun remainingTicks(playerId: PlayerId, weaponId: WeaponId): Long

    /** Start (or restart) a cooldown of [durationTicks] for the given weapon. */
    fun start(playerId: PlayerId, weaponId: WeaponId, durationTicks: Int)
}
