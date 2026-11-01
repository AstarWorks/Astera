package com.astarworks.astera.bots

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Stateful target choice of a bot: line of sight, low-HP and threat weighting, hysteresis and a short memory of the
 * last target. Pure and deterministic (the clock is passed in, no randomness), so a game-sim scenario drives the very
 * decision [BotBrain] makes. Eligibility (self, team, spectators) is [TargetPolicy.eligible].
 *
 * score = distance - [LOW_HP_BONUS] * (1 - hp) - [THREAT_BONUS] (attacker of me) - [STICKY_BONUS] (current target);
 * the lowest score wins. Only enemies with line of sight can be a NEW target; the current target keeps its place
 * while visible. When the current target leaves sight and nobody else is visible it is remembered for [MEMORY_MS] at its
 * last known position ([Choice.remembered]) so the bot chases there, then dropped.
 */
class TargetSelector {
    /**
     * [target] = the enemy to fight (null = nobody). [remembered] = it is out of sight: walk to [lastX]/[lastY]/[lastZ]
     * (where it was last seen) instead of aiming or shooting at it.
     */
    data class Choice(val target: TargetPolicy.Candidate?, val remembered: Boolean, val lastX: Double, val lastY: Double, val lastZ: Double)

    private var currentId: Int? = null
    private var lastSeenAt = 0L
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var lastCand: TargetPolicy.Candidate? = null
    private val attackedMeAt = HashMap<Int, Long>()

    /** The bot took damage from the player entity [id] (BotBrain: the attacker of a damage event). */
    fun noteAttackedBy(id: Int, now: Long) { attackedMeAt[id] = now }

    /** Forget everything (respawn, new match). */
    fun reset() { currentId = null; lastCand = null; attackedMeAt.clear() }

    fun select(
        now: Long, selfName: String, selfTeam: String?, x: Double, y: Double, z: Double,
        candidates: Iterable<TargetPolicy.Candidate>, sightRange: Double = SIGHT_RANGE,
    ): Choice {
        var best: TargetPolicy.Candidate? = null
        var bestScore = Double.MAX_VALUE
        for (c in candidates) {
            if (!TargetPolicy.eligible(selfName, selfTeam, c)) continue
            if (!c.hasLos) continue
            val d = hypot(c.x - x, c.z - z) + abs(c.y - y) * 0.5
            if (d > sightRange) continue
            val s = score(c, d, now)
            if (s < bestScore) { best = c; bestScore = s }
        }
        if (best != null) {
            currentId = best.id; lastSeenAt = now; lastX = best.x; lastY = best.y; lastZ = best.z; lastCand = best
            return Choice(best, false, best.x, best.y, best.z)
        }
        val mem = lastCand
        if (mem != null && now - lastSeenAt <= MEMORY_MS) return Choice(mem, true, lastX, lastY, lastZ)
        currentId = null; lastCand = null
        return Choice(null, false, 0.0, 0.0, 0.0)
    }

    private fun score(c: TargetPolicy.Candidate, dist: Double, now: Long): Double {
        val hp = (c.hpFraction ?: 1.0).coerceIn(0.0, 1.0)
        val threat = attackedMeAt[c.id]?.let { now - it <= THREAT_WINDOW_MS } == true
        return dist - LOW_HP_BONUS * (1.0 - hp) - (if (threat) THREAT_BONUS else 0.0) - (if (c.id == currentId) STICKY_BONUS else 0.0)
    }

    companion object {
        /** Same sight range as the nearest-enemy rule (blocks). */
        const val SIGHT_RANGE = 70.0

        /** Full health to dead is worth this many blocks of distance (easy kill). */
        const val LOW_HP_BONUS = 20.0

        /** An enemy that damaged the bot within [THREAT_WINDOW_MS] counts this many blocks nearer. */
        const val THREAT_BONUS = 15.0
        const val THREAT_WINDOW_MS = 4_000L

        /** Hysteresis: the current target counts this many blocks nearer so the bot does not flip every tick. */
        const val STICKY_BONUS = 8.0

        /** How long a target that left sight is chased to its last known position. */
        const val MEMORY_MS = 4_000L
    }
}
