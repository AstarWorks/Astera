package com.astarworks.astera.bots

/**
 * Whether a bot sprint-jumps this tick. Pure (no clock, no protocol) so a game-sim scenario can drive the decision
 * [BotBrain] makes. Movement speed values are never touched: a sprint-jumping client simply covers ground faster
 * than a walking one, which is what a real player does when travelling.
 *
 * Only non-combat travel sprint-jumps (walking to an objective, stars, waypoints, roaming). Combat movement
 * (chasing, strafing, kiting, reloading) is left exactly as it was.
 */
object SprintJumpPolicy {
    data class Decision(val sprint: Boolean, val jump: Boolean) {
        companion object { val NONE = Decision(sprint = false, jump = false) }
    }

    /**
     * @param inCombat an enemy is being fought (or fled from, or a reload runs): never sprint or jump
     * @param moving the bot is going somewhere this tick (false when stopped or arrived)
     * @param airborne a jump is already in progress
     * @param groundAhead solid, known floor a little ahead along the heading (no edge, hole or unloaded chunk)
     * @param headroom free air above the bot for the whole jump (no ceiling to bump into)
     * @param stepUp the route climbs a block right ahead: the bot has to hop onto it (a bare position jump of one block is not walkable)
     * @param forceJump a stuck escape asks for a hop
     */
    fun decide(
        inCombat: Boolean, moving: Boolean, airborne: Boolean, groundAhead: Boolean, headroom: Boolean,
        stepUp: Boolean = false, forceJump: Boolean = false,
    ): Decision {
        if (inCombat || !moving) return Decision.NONE
        return Decision(sprint = true, jump = !airborne && headroom && (groundAhead || stepUp || forceJump))
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
