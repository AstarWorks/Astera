package com.astarworks.astera.bots

/**
 * Which of the (at most two, per the Norma layer weapon limit) carried weapons a bot holds right now.
 * Pure and deterministic - no clock, no randomness, no protocol - so a game-sim scenario can drive the very
 * same decision the live [BotBrain] makes.
 *
 * Sword up close; at range the gun, or the staff while the gun is empty / reloading; during a reload with
 * no staff the sword covers a close enemy; an empty gun is still selected at range so the brain can reload it.
 */
object WeaponPolicy {
    /** Switch to melee at or below this distance (blocks) ... */
    const val MELEE_SWITCH = 4.0

    /** ... and only switch back to a ranged item beyond this, so the bot does not flap on the boundary. */
    const val MELEE_RELEASE = 5.0

    /** While the gun reloads and there is no staff, the sword is held for an enemy closer than this. */
    const val RELOAD_COVER = 8.0

    fun choose(
        loadout: List<Weapon>,
        dist: Double,
        now: Long,
        reloadBusyUntil: Long,
        held: Weapon? = null,
    ): Weapon? {
        val melee = loadout.firstOrNull { it.kind == WeaponKind.MELEE }
        val gun = loadout.firstOrNull { it.kind == WeaponKind.RANGED && it.magazine }
        val caster = loadout.firstOrNull { it.kind == WeaponKind.RANGED && !it.magazine }
        val reloading = now < reloadBusyUntil
        val meleeRange = if (held?.kind == WeaponKind.MELEE) MELEE_RELEASE else MELEE_SWITCH
        if (melee != null && dist <= meleeRange) return melee
        val ranged = when {
            gun != null && !reloading -> gun
            caster != null -> caster
            melee != null && reloading && dist <= RELOAD_COVER -> melee
            else -> gun
        }
        return ranged ?: melee
    }
}
