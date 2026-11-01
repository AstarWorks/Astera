package com.astarworks.astera.bots

/**
 * Which of the (at most two, per the Norma layer weapon limit) carried weapons a bot holds right now.
 * Pure and deterministic - no clock, no randomness, no protocol - so a game-sim scenario can drive the very
 * same decision the live [BotBrain] makes.
 *
 * A bot that carries a melee weapon CLOSES IN with it (within [MELEE_SWITCH]; the brain walks up and swings) instead of
 * kiting at the ranged preferred distance; beyond that the gun fires while the bot approaches. At range the gun, or the staff while the gun is empty / reloading; during a reload with
 * no staff the sword covers a close enemy; an empty gun is still selected at range so the brain can reload it.
 */
object WeaponPolicy {
    /** Switch to melee at or below this distance (blocks): a sword bot starts its rush from here ... */
    const val MELEE_SWITCH = 10.0

    /** ... and only switch back to a ranged item beyond this, so the bot does not flap on the boundary. */
    const val MELEE_RELEASE = 12.0

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

    // --- engagement ranges (ranged weapons) -------------------------------------------------------------------------

    /** A ranged bot backs off when the enemy is closer than this ... */
    const val RANGED_MIN = 10.0

    /** ... and walks up when it is farther than this; in between it strafes. */
    const val RANGED_MAX = 25.0

    /** The most distant shot taken, and only with line of sight (a shot into a wall is wasted ammo). */
    const val FIRE_MAX = 40.0

    enum class Move { CLOSE_IN, APPROACH, BACK_OFF, STRAFE }

    /** Fire only with line of sight and within [FIRE_MAX]. A remembered target that is out of sight is chased, never shot at. */
    fun mayFire(dist: Double, hasLos: Boolean): Boolean = hasLos && dist <= FIRE_MAX

    /**
     * Movement of the fight: melee closes in; a ranged weapon holds [RANGED_MIN]..[RANGED_MAX], approaches beyond it and
     * backs off inside it. Without line of sight the bot always approaches (to get one) whatever the weapon.
     */
    fun move(weapon: Weapon?, flat: Double, hasLos: Boolean = true): Move = when {
        weapon == null || weapon.kind == WeaponKind.MELEE -> Move.CLOSE_IN
        !hasLos -> Move.APPROACH
        flat > RANGED_MAX -> Move.APPROACH
        flat < RANGED_MIN -> Move.BACK_OFF
        else -> Move.STRAFE
    }
}
