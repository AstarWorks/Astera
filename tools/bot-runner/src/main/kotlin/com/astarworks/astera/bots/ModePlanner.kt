package com.astarworks.astera.bots

import kotlin.math.hypot

/**
 * What a bot does for the game mode when nobody is in sight (item: per-mode objectives). Pure and deterministic: the
 * clock is passed in, state advances only from the bot's own position (standing in a region is how every siege mode rewards
 * the player), so a game-sim scenario can drive the real mode with it.
 *
 *  - `break-core`: attackers dig the enemy core, every third bot (by name hash) defends its own core.
 *  - `carry-mini-tnt`: collect mini TNT at the pickup spots and plant it in the enemy base. A carrier that dies starts over.
 *  - `carry-tnt`: take the TNT at `tnt-spawn` to the enemy base.
 *  - `steal-jewelry`: take a jewel from the enemy pedestal, bank it at the own base.
 *
 * Pickups and deliveries are short holds in the mode (a progress bar fills), so a step only counts as done once the bot
 * has stood inside its radius for [HOLD_MS].
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
    private val pickupCooldownMs: Long = 15_000,
) {
    /** Stage regions by name (world coordinates). */
    interface Zones {
        fun point(name: String): Triple<Double, Double, Double>?
        fun names(prefix: String): List<String>
    }

    enum class Role { ATTACK, DEFEND }

    /** Where to go: [kind] is one of `attack-core`, `defend-core`, `pickup`, `bank`, `tnt`, `deliver`, `jewel`, `wool`, `recover`. */
    data class Step(val zone: String, val x: Double, val y: Double, val z: Double, val arrive: Double, val kind: String, val breakable: Boolean = false)

    val role: Role = if (modeId == "break-core" && Math.floorMod(botName.hashCode(), 3) == 0) Role.DEFEND else Role.ATTACK

    // Inferred state (the server does not tell a client what it carries).
    var carried = 0; private set
    var holdingTnt = false; private set
    private val takenAt = HashMap<String, Long>()
    private var woolIndex = 0
    private var holdSince = 0L
    private var dwellSince = 0L

    fun handles(): Boolean = modeId in MODES

    /** Where the bot's carry fell when it died (jewels / TNT are dropped in place); it goes back for it. */
    private var lostX = 0.0
    private var lostZ = 0.0
    private var lostPending = false
    private var recoverUntil = 0L

    /** Loose objective markers the brain currently sees in the world (a dropped pile / TNT), filtered against the fixed stage points. */
    private var looseSeen: List<Pair<Double, Double>> = emptyList()

    /**
     * The bot died at ([x], [z]): whatever it carried is lost where it fell. A carrier of TNT or jewels remembers the
     * spot and re-picks it after the respawn instead of starting the whole plan over.
     */
    fun onDeath(x: Double, z: Double) {
        if (holdingTnt || (modeId == "steal-jewelry" && carried > 0)) {
            lostX = x; lostZ = z; lostPending = true; recoverUntil = 0L
        }
        carried = 0; holdingTnt = false; holdSince = 0L; dwellSince = 0L
    }

    /** Positions of block-display markers in sight; the ones that are not a fixed stage point are loose objectives. */
    fun noteMarkers(markers: List<Pair<Double, Double>>) {
        if (modeId != "carry-tnt" && modeId != "steal-jewelry") { looseSeen = emptyList(); return }
        val fixed = (zones.names("jewelry-") + zones.names("base-") + zones.names("wool-") + zones.names("core-") + TNT_SPAWN)
            .mapNotNull { zones.point(it) }
        looseSeen = markers.filter { m -> fixed.none { hypot(it.first - m.first, it.third - m.second) <= FIXED_CLEARANCE } }
    }

    /** Heads for a dropped objective (own death spot first, else the nearest loose marker); null when there is none to chase. */
    private fun recover(now: Long, x: Double, z: Double): Step? {
        var tx: Double; var tz: Double
        if (lostPending) {
            if (recoverUntil == 0L) recoverUntil = now + RECOVER_MS
            if (now > recoverUntil) { lostPending = false; return null }
            tx = lostX; tz = lostZ
        } else {
            val near = looseSeen.minByOrNull { hypot(it.first - x, it.second - z) }
                ?.takeIf { hypot(it.first - x, it.second - z) <= LOOSE_RANGE } ?: return null
            tx = near.first; tz = near.second
        }
        val y = zones.point(if (modeId == "carry-tnt") TNT_SPAWN else "base-$ownTeam")?.second ?: return null
        val s = Step("loose", tx, y, tz, 1.5, "recover")
        if (held(s, now, x, z)) {
            lostPending = false
            if (modeId == "carry-tnt") holdingTnt = true else carried = 1
            return step(now, x, z)
        }
        return s
    }

    /** The step for a bot at ([x], [z]) at time [now]; null when the mode has no plan or the stage lacks the regions. */
    fun step(now: Long, x: Double, z: Double): Step? = when (modeId) {
        "break-core" -> breakCore()
        "carry-tnt" -> (if (holdingTnt) null else recover(now, x, z)) ?: carryTnt(now, x, z)
        "carry-mini-tnt" -> miniTnt(now, x, z)
        "steal-jewelry" -> (if (carried > 0) null else recover(now, x, z)) ?: jewelry(now, x, z)
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

    /** True once the bot has stood inside [s]'s radius for [HOLD_MS]: the modes' pickups and deliveries are short holds, not touches. */
    private fun held(s: Step, now: Long, x: Double, z: Double): Boolean {
        if (!reached(s, x, z)) { dwellSince = 0L; return false }
        if (dwellSince == 0L) dwellSince = now
        if (now - dwellSince < HOLD_MS) return false
        dwellSince = 0L
        return true
    }

    private fun carryTnt(now: Long, x: Double, z: Double): Step? {
        if (holdingTnt) {
            val s = nearestEnemy("base-", x, z)?.let { step(it, "deliver", BASE_RADIUS) } ?: return null
            if (held(s, now, x, z)) { holdingTnt = false; return carryTnt(now, x, z) }
            return s
        }
        val s = step(TNT_SPAWN, "tnt", 1.5) ?: return null
        if (held(s, now, x, z)) { holdingTnt = true; return carryTnt(now, x, z) }
        return s
    }

    private fun miniTnt(now: Long, x: Double, z: Double): Step? {
        val pickups = zones.names("wool-")
        val free = pickups.filter { now - (takenAt[it] ?: -pickupCooldownMs) >= pickupCooldownMs }
        // Plant in the enemy base when the hands are full or nothing is left to take.
        if (carried >= carryLimit || (carried > 0 && free.isEmpty())) {
            val s = nearestEnemy("base-", x, z)?.let { step(it, "deliver", BASE_RADIUS) } ?: return null
            if (held(s, now, x, z)) { carried = 0; return miniTnt(now, x, z) }
            return s
        }
        val target = nearest(free.ifEmpty { pickups }, x, z) ?: return null
        val s = step(target, "pickup", 1.5) ?: return null
        if (target in free && held(s, now, x, z)) {
            takenAt[target] = now; carried++
            return miniTnt(now, x, z)
        }
        return s
    }

    private fun jewelry(now: Long, x: Double, z: Double): Step? {
        if (carried > 0) {
            val s = step("base-$ownTeam", "bank", BASE_RADIUS) ?: return null
            if (held(s, now, x, z)) { carried = 0; return jewelry(now, x, z) }
            return s
        }
        val zone = nearestEnemy("jewelry-", x, z) ?: return null
        val s = step(zone, "jewel", 1.5) ?: return null
        if (held(s, now, x, z)) { carried = 1; return jewelry(now, x, z) }
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
        const val HOLD_MS = 1_500L
        const val RECOVER_MS = 25_000L
        const val LOOSE_RANGE = 40.0
        const val FIXED_CLEARANCE = 5.0
        const val WOOL_HOLD_MS = 12_000L
    }
}
