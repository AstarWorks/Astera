package com.astarworks.astera.bots

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Which visible player a bot fights. Pure (no clock, no protocol) so a game-sim scenario can drive the very same
 * decision [BotBrain] makes.
 *
 * Never: itself, a team mate, or a spectator. Dead players wait out their respawn in spectator mode (the death cam);
 * the server ignores them as targets (`PaperNearbyTargetQuery`, `PaperDamageGateway`), so a bot that picked one
 * would stand over the corpse swinging at nothing instead of walking on to stars, the objective or a living enemy.
 */
object TargetPolicy {
    /** A player entity the bot can see: [team] is its scoreboard team (null = none), [spectating] its tab-list game mode. */
    data class Candidate(
        val id: Int,
        val name: String,
        val team: String?,
        val spectating: Boolean,
        val x: Double,
        val y: Double,
        val z: Double,
    )

    fun eligible(selfName: String, selfTeam: String?, c: Candidate): Boolean =
        c.name != selfName && !c.spectating && !(selfTeam != null && c.team == selfTeam)

    /** The nearest eligible candidate within [sightRange] (flat distance plus half the height difference), or null. */
    fun nearest(selfName: String, selfTeam: String?, x: Double, y: Double, z: Double, candidates: Iterable<Candidate>, sightRange: Double): Candidate? {
        var best: Candidate? = null
        var bestD = Double.MAX_VALUE
        for (c in candidates) {
            if (!eligible(selfName, selfTeam, c)) continue
            val d = hypot(c.x - x, c.z - z) + abs(c.y - y) * 0.5
            if (d < bestD && d <= sightRange) { best = c; bestD = d }
        }
        return best
    }
}
