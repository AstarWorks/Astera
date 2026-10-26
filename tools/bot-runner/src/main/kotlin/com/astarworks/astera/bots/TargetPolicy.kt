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
    /**
     * A player entity the bot can see: [team] is its scoreboard team (null = none), [spectating] its tab-list game mode.
     * [listed] false = the entity has no tab-list entry (the server may hide the enemy team from the Tab list): then
     * neither name, team nor game mode are known, so it is judged only by what the entity itself shows (see
     * [IneffectiveTargets] for how a dead or untouchable one is dropped).
     */
    data class Candidate(
        val id: Int,
        val name: String,
        val team: String?,
        val spectating: Boolean,
        val x: Double,
        val y: Double,
        val z: Double,
        val listed: Boolean = true,
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

/**
 * The tab-independent answer to "is this one really attackable": a target the bot has been fighting for
 * [graceMs] without a single hit landing (no floating damage number) is ignored for [ignoreMs]. Covers a spectator in
 * the death cam, a protected player and a decorative player-entity when the Tab list does not tell the game mode.
 * Pure: the clock and the hit counter are passed in.
 */
class IneffectiveTargets(private val graceMs: Long = 5_000, private val ignoreMs: Long = 12_000, private val minAttacks: Int = 3) {
    private var id = Int.MIN_VALUE
    private var since = 0L
    private var hitsAtStart = 0
    private var attacksAtStart = 0
    private val ignoredUntil = HashMap<Int, Long>()

    fun ignored(targetId: Int, now: Long): Boolean = (ignoredUntil[targetId] ?: 0L) > now

    /** Call each tick the bot engages [targetId]; [hits] = hits landed so far, [attacks] = attacks sent so far (both monotonic). */
    fun engage(targetId: Int, now: Long, hits: Int, attacks: Int) {
        if (targetId != id) { id = targetId; since = now; hitsAtStart = hits; attacksAtStart = attacks; return }
        if (hits != hitsAtStart) { since = now; hitsAtStart = hits; attacksAtStart = attacks; return }
        if (now - since >= graceMs && attacks - attacksAtStart >= minAttacks) {
            ignoredUntil[targetId] = now + ignoreMs
            id = Int.MIN_VALUE
        }
    }

    /** The bot is no longer engaging anybody. */
    fun disengage() { id = Int.MIN_VALUE }
}
