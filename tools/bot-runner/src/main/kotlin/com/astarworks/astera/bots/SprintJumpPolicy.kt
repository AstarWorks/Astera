package com.astarworks.astera.bots

/**
 * How a bot travels this tick: combat state plus the dash-jump decision. Pure (no clock, no protocol) so a game-sim
 * scenario can drive the decision [BotBrain] makes.
 *
 * The dash-jump is the player's sprint-jump: sprint plus a vanilla jump arc, covering [DASH_JUMP_SPEED] blocks per
 * tick (a walking bot covers [WALK_SPEED]). It is used only for long trips ([DASH_MIN_DISTANCE] or more left);
 * short moves walk, and only hop (the normal walking jump, no sprint) to climb a step or break out of a stuck spot.
 * Combat movement (fighting, fleeing, reloading under fire) never dashes.
 */
object SprintJumpPolicy {
    /** [speed]: blocks per tick the bot may cover this tick. */
    data class Decision(val sprint: Boolean, val jump: Boolean, val speed: Double = WALK_SPEED) {
        companion object { val NONE = Decision(sprint = false, jump = false) }
    }

    const val WALK_SPEED = 0.23

    /** An enemy closer than this (flat blocks) is a fight; farther away it is somebody to travel to. */
    const val ENGAGE_RANGE = 12.0

    /** Remaining trip length from which the dash-jump is worth it. */
    const val DASH_MIN_DISTANCE = 12.0

    /** Vanilla sprint on the ground (blocks per tick). */
    const val SPRINT_SPEED = 0.2806

    /** Vanilla repeated sprint-jump (about 7.1 blocks per second). */
    const val DASH_JUMP_SPEED = 0.356

    /**
     * Fighting means an enemy within [ENGAGE_RANGE], a retreat, or a reload with an enemy in sight. An enemy that is
     * merely visible far away (the sight range is 70 blocks) is not a fight: the bot is travelling towards it.
     */
    fun inCombat(enemyFlat: Double?, retreating: Boolean, reloading: Boolean): Boolean =
        retreating || (enemyFlat != null && (reloading || enemyFlat < ENGAGE_RANGE))

    /**
     * @param inCombat see [inCombat]: never sprint or jump
     * @param moving the bot is going somewhere this tick (false when stopped or arrived)
     * @param airborne a jump is already in progress
     * @param groundAhead solid, known floor a little ahead along the heading (no edge, hole or unloaded chunk)
     * @param headroom free air above the bot for the whole jump (no ceiling to bump into)
     * @param stepUp the route climbs a block right ahead: the bot has to hop onto it
     * @param forceJump a stuck escape asks for a hop
     * @param distance flat distance left to the destination
     * @param dashing the jump in progress is a dash-jump (only meaningful when [airborne])
     */
    fun decide(
        inCombat: Boolean, moving: Boolean, airborne: Boolean, groundAhead: Boolean, headroom: Boolean,
        stepUp: Boolean = false, forceJump: Boolean = false,
        distance: Double = 0.0, dashing: Boolean = false, walkSpeed: Double = WALK_SPEED,
    ): Decision {
        if (inCombat || !moving) return Decision(false, false, walkSpeed)
        if (airborne) return if (dashing) Decision(true, false, DASH_JUMP_SPEED) else Decision(false, false, walkSpeed)
        if (distance >= DASH_MIN_DISTANCE) {
            val jump = headroom && groundAhead
            return Decision(sprint = true, jump = jump, speed = if (jump) DASH_JUMP_SPEED else SPRINT_SPEED)
        }
        return Decision(sprint = false, jump = headroom && (stepUp || forceJump), speed = walkSpeed)
    }

    /** Height above the floor for each tick of a vanilla jump (initial velocity 0.42, gravity 0.08, drag 0.98); ends with 0.0 (landed). */
    val ARC: DoubleArray = run {
        val out = ArrayList<Double>()
        var h = 0.0
        var v = 0.42
        while (true) {
            h += v
            v = (v - 0.08) * 0.98
            if (h <= 0.0) break
            out += h
        }
        out.toDoubleArray()
    }

    /** Highest point of the arc, for the headroom check. */
    val PEAK: Double = ARC.max()

    /** Lift for the tick after [step] ticks of jumping (step 0 = first airborne tick); 0.0 once the arc is over. */
    fun lift(step: Int): Double = ARC.getOrElse(step) { 0.0 }
}
