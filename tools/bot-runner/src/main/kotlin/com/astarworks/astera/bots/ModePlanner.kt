package com.astarworks.astera.bots

import kotlin.math.hypot

/**
 * What a bot does for the game mode when nobody is in sight (item: per-mode objectives). Pure and deterministic: the
 * clock is passed in, state advances only from the bot's own position (region entry is how every siege mode rewards
 * the player), so a game-sim scenario can drive the real mode with it.
 *
 *  - `break-core`: attackers dig the enemy core, every third bot (by name hash) defends its own core.
 *  - `carry-mini-tnt`: collect mini TNT at the pickup spots, bank at the own base, once the pool should hold enough
 *    take the combined TNT at `tnt-spawn` to the enemy base. A carrier that dies starts over.
 *  - `carry-tnt`: take the TNT at `tnt-spawn` to the enemy base.
 *  - `steal-jewelry`: take a jewel from the enemy pedestal, bank it at the own base.
 *  - `capture-wool`: hold the wool zones one after the other.
 *  - anything else: no plan (the brain falls back to [ObjectiveSource.objectiveFor]).
 */
class ModePlanner(
    private val modeId: String,
    private val botName: String,
    private val ownTeam: String,
    private val enemyTeams: List<String>,
    private val zones: Zones,
    private val carryLimit: Int = 4,
    private val minisPerTnt: Int = 10,
    private val pickupCooldownMs: Long = 15_000,
) {
    /** Stage regions by name (world coordinates). */
    interface Zones {
        fun point(name: String): Triple<Double, Double, Double>?
        fun names(prefix: String): List<String>
    }

    enum class Role { ATTACK, DEFEND }

    /** Where to go: [kind] is one of `attack-core`, `defend-core`, `pickup`, `bank`, `tnt`, `deliver`, `jewel`, `wool`. */
    data class Step(val zone: String, val x: Double, val y: Double, val z: Double, val arrive: Double, val kind: String, val breakable: Boolean = false)

    val role: Role = if (modeId == "break-core" && Math.floorMod(botName.hashCode(), 3) == 0) Role.DEFEND else Role.ATTACK

    // Inferred state (the server does not tell a client what it carries).
    var carried = 0; private set
    var pooled = 0; private set
    var holdingTnt = false; private set
    private var wantTnt = false
    private var tntCheckedAt = 0L
    private val takenAt = HashMap<String, Long>()
    private var woolIndex = 0
    private var holdSince = 0L

    fun handles(): Boolean = modeId in MODES

    /** The bot died: whatever it carried is lost. */
    fun onDeath() { carried = 0; holdingTnt = false; holdSince = 0L }

    /** The step for a bot at ([x], [z]) at time [now]; null when the mode has no plan or the stage lacks the regions. */
    fun step(now: Long, x: Double, z: Double): Step? = when (modeId) {
        "break-core" -> breakCore()
        "carry-tnt" -> carryTnt(x, z)
        "carry-mini-tnt" -> miniTnt(now, x, z)
        "steal-jewelry" -> jewelry(x, z)
        "capture-wool" -> wool(now, x, z)
        else -> null
    }

    private fun step(zone: String, kind: String, arrive: Double, breakable: Boolean = false): Step? =
        zones.point(zone)?.let { Step(zone, it.first, it.second, it.third, arrive, kind, breakable) }

    private fun reached(s: Step, x: Double, z: Double) = hypot(s.x - x, s.z - z) <= s.arrive

    private fun nearest(candidates: List<String>, x: Double, z: Double): String? =
        candidates.mapNotNull { n -> zones.point(n)?.let { n to hypot(it.first - x, it.third - z) } }.minByOrNull { it.second }?.first

    private fun nearestEnemy(prefix: String, x: Double, z: Double) = nearest(enemyTeams.map { "$prefix$it" }, x, z)

    private fun breakCore(): Step? {
        if (role == Role.DEFEND) return step("core-$ownTeam", "defend-core", DEFEND_RADIUS)
        val zone = nearestEnemy("core-", 0.0, 0.0) ?: return null
        return step(zone, "attack-core", 3.5, breakable = true)
    }

    private fun carryTnt(x: Double, z: Double): Step? {
        if (holdingTnt) {
            val s = nearestEnemy("base-", x, z)?.let { step(it, "deliver", BASE_RADIUS) } ?: return null
            if (reached(s, x, z)) { holdingTnt = false; return carryTnt(x, z) }
            return s
        }
        val s = step(TNT_SPAWN, "tnt", 1.5) ?: return null
        if (reached(s, x, z)) { holdingTnt = true; return carryTnt(x, z) }
        return s
    }

    private fun miniTnt(now: Long, x: Double, z: Double): Step? {
        if (holdingTnt) {
            val s = nearestEnemy("base-", x, z)?.let { step(it, "deliver", BASE_RADIUS) } ?: return null
            if (reached(s, x, z)) { holdingTnt = false; return miniTnt(now, x, z) }
            return s
        }
        val pickups = zones.names("wool-")
        val free = pickups.filter { now - (takenAt[it] ?: -pickupCooldownMs) >= pickupCooldownMs }
        // Bank when the hands are full or nothing is left to take.
        if (carried >= carryLimit || (carried > 0 && free.isEmpty())) {
            val s = step("base-$ownTeam", "bank", BASE_RADIUS) ?: return null
            if (reached(s, x, z)) {
                pooled += carried; carried = 0
                if (pooled >= minisPerTnt) { pooled -= minisPerTnt; wantTnt = true }
                return miniTnt(now, x, z)
            }
            return s
        }
        // The combined TNT: when our pool should have produced one, or to look once a minute (a mate may have banked the rest).
        if (wantTnt || (pooled + carried > 0 && now - tntCheckedAt > TNT_CHECK_MS)) {
            val s = step(TNT_SPAWN, "tnt", 1.5) ?: return null
            if (reached(s, x, z)) { holdingTnt = true; wantTnt = false; tntCheckedAt = now; return miniTnt(now, x, z) }
            return s
        }
        val target = nearest(free.ifEmpty { pickups }, x, z) ?: return null
        val s = step(target, "pickup", 1.5) ?: return null
        if (target in free && reached(s, x, z)) {
            takenAt[target] = now; carried++
            return miniTnt(now, x, z)
        }
        return s
    }

    private fun jewelry(x: Double, z: Double): Step? {
        if (carried > 0) {
            val s = step("base-$ownTeam", "bank", BASE_RADIUS) ?: return null
            if (reached(s, x, z)) { carried = 0; return jewelry(x, z) }
            return s
        }
        val zone = nearestEnemy("jewelry-", x, z) ?: return null
        val s = step(zone, "jewel", 1.5) ?: return null
        if (reached(s, x, z)) { carried = 1; return jewelry(x, z) }
        return s
    }

    private fun wool(now: Long, x: Double, z: Double): Step? {
        val names = zones.names("wool-")
        if (names.isEmpty()) return null
        val zone = names[Math.floorMod(Math.floorMod(botName.hashCode(), names.size) + woolIndex, names.size)]
        val s = step(zone, "wool", 1.5) ?: return null
        if (!reached(s, x, z)) { holdSince = 0L; return s }
        if (holdSince == 0L) holdSince = now
        if (now - holdSince >= WOOL_HOLD_MS) { woolIndex++; holdSince = 0L }
        return s
    }

    companion object {
        val MODES = setOf("break-core", "carry-tnt", "carry-mini-tnt", "steal-jewelry", "capture-wool")
        const val TNT_SPAWN = "tnt-spawn"
        const val BASE_RADIUS = 3.0
        const val DEFEND_RADIUS = 7.0
        const val TNT_CHECK_MS = 60_000L
        const val WOOL_HOLD_MS = 12_000L
    }
}
