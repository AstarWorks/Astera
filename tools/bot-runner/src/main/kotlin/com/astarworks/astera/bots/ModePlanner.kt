package com.astarworks.astera.bots

import kotlin.math.hypot

/**
 * What a bot does for the game mode when nobody is in sight (item BOT-04: team roles per mode). Pure and deterministic:
 * the clock is passed in, state advances only from the bot's own position (standing in a region is how every siege mode
 * rewards the player), so a game-sim scenario can drive the real mode with it.
 *
 * Each call asks [RoleAssigner] which [Role] this bot plays NOW (from the team roster and the [ModeFacts]) and turns the
 * role into a [Step]. Roles are recomputed on every call, so a dead carrier, a lost zone or an opened barrier re-splits the squad.
 *
 *  - `carry-tnt` (Rainmaker): shield breakers stand in `tnt-spawn`; the carrier takes the TNT through `checkpoint-<enemy>-<n>`
 *    to `deliver-<enemy>`; escorts shadow the carrier; interceptors chase the enemy carrier, goal guards hold `deliver-<own>`.
 *  - `capture-wool` (Splat Zones): holders stay in the zones the team owns, contesters / retakers work the others.
 *  - `carry-mini-tnt` (Clam Blitz): collectors take `clam-<n>` until the team's hands add up to a power TNT, its holder throws
 *    it at `basket-<enemy>` (opening the barrier), rushers plant the minis they carry while the barrier is open, a guard
 *    stays at `basket-<own>`.
 *  - `steal-jewelry`: thieves take a jewel from a stocked `jewelry-<enemy>` (a long channel that damage cancels) and bank it
 *    at `base-<own>`; guards hold `jewelry-<own>` and chase every enemy thief that runs with a jewel.
 *  - `annihilation`: attackers dig the lowest standing pillar of the weakest enemy team, a defender stays at the own pillars.
 *
 * Pickups and deliveries are short holds in the mode (a progress bar fills), so a step only counts as done once the bot
 * has stood inside its radius for [HOLD_MS].
 */
class ModePlanner(
    private val modeId: String,
    private val botName: String,
    private val ownTeam: String,
    private val enemyTeams: List<String>,
    private val zones: Zones,
    private val carryLimit: Int = 8,
    private val pickupCooldownMs: Long = 20_000,
    private val facts: ModeFacts = ModeFacts.NONE,
) {
    /** Stage regions by name (world coordinates). */
    interface Zones {
        fun point(name: String): Triple<Double, Double, Double>?
        fun names(prefix: String): List<String>

        /** Annihilation pillar sites (block coordinates) of every team. */
        fun pillars(): List<PillarSite> = emptyList()
    }

    /** One pillar block of an Annihilation stage: [x]/[y]/[z] are the block coordinates. */
    data class PillarSite(val team: String, val tier: Int, val x: Int, val y: Int, val z: Int, val affinity: String? = null) {
        /** Tool class that digs this pillar fastest: the stage's `affinity`, else the tier default. */
        fun toolClass(): LoadoutClassifier.Affinity = LoadoutClassifier.parseAffinity(affinity) ?: LoadoutClassifier.defaultAffinity(tier)
    }

    /**
     * Where to go: [kind] is one of `pickup`, `bank`, `tnt`, `deliver`, `jewel`, `wool`, `recover`, `shield`, `checkpoint`,
     * `escort`, `intercept`, `guard`, `stage`, `pillar`. [engage] is how far away an enemy may be for the brain to
     * leave the step and fight it (a carrier runs, a guard stays near its post). [strike] is a block to dig on arrival.
     */
    data class Step(
        val zone: String, val x: Double, val y: Double, val z: Double, val arrive: Double, val kind: String,
        val role: Role? = null, val engage: Double = Double.MAX_VALUE, val strike: Triple<Int, Int, Int>? = null,
        val affinity: LoadoutClassifier.Affinity? = null,
    )

    // Inferred state (the server does not tell a client what it carries).
    var carried = 0; private set
    var holdingTnt = false; private set
    private val takenAt = HashMap<String, Long>()
    private var dwellSince = 0L
    private val secured = HashSet<String>()

    /** Role of the last step handed out (traces, scenarios), and the engage distance of that step for the brain's fight check. */
    var role: Role? = null; private set
    val engage: Double get() = lastEngage
    private var lastEngage = Double.MAX_VALUE

    fun handles(): Boolean = modeId in MODES

    private var roster: List<Mate> = emptyList()

    /** The team as the bot sees it (see [RoleAssigner]); refresh it every tick. */
    fun noteRoster(mates: List<Mate>) { roster = mates }

    /** Where the bot's carry fell when it died (jewels / TNT are dropped in place); it goes back for it. */
    private var lostX = 0.0
    private var lostZ = 0.0
    private var lostPending = false
    private var recoverUntil = 0L

    /** Wool zones the team owned when last looked at and lost since: they are RETAKEN, not just contested. */
    private val woolOwner = HashMap<String, String?>()
    private val woolLost = HashSet<String>()

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
        carried = 0; holdingTnt = false; dwellSince = 0L; awaitingSince = 0L
        secured.clear()
    }

    /**
     * Positions of the loose objective markers in sight (the mode tags exactly these entities, see [LOOSE_TAG]; carried
     * stacks, pedestals and goal markers are never tagged, so carriers are not mistaken for dropped objectives).
     */
    fun noteMarkers(markers: List<Pair<Double, Double>>) {
        looseSeen = if (modeId == "carry-tnt" || modeId == "steal-jewelry") markers else emptyList()
    }

    /** Total pickups the environment has confirmed so far (the pickup cue the server plays for the holder only). */
    fun noteConfirmed(total: Int) { confirmed = total }

    private var confirmed = 0
    private var seenConfirmed = 0
    private var awaitingSince = 0L

    /**
     * A pickup counts only once the server confirmed it: after the hold completes the bot keeps standing on [s] until
     * [noteConfirmed] advances (then [apply] runs and true is returned), or [CONFIRM_MS] pass and the hold is retried.
     */
    private fun pickedUp(s: Step, now: Long, x: Double, z: Double, holdMs: Long = HOLD_MS, apply: () -> Unit): Boolean {
        if (awaitingSince != 0L) {
            if (confirmed > seenConfirmed) {
                seenConfirmed = confirmed; awaitingSince = 0L; apply(); return true
            }
            if (now - awaitingSince > CONFIRM_MS) awaitingSince = 0L
            return false
        }
        if (held(s, now, x, z, holdMs)) awaitingSince = now
        return false
    }

    /** Heads for a dropped objective (own death spot first, else the nearest loose marker); null when there is none to chase. */
    private fun recover(now: Long, x: Double, z: Double): Step? {
        val tx: Double; val tz: Double
        if (lostPending) {
            if (recoverUntil == 0L) recoverUntil = now + RECOVER_MS
            if (now > recoverUntil) { lostPending = false; return null }
            tx = lostX; tz = lostZ
        } else {
            val near = looseSeen.minByOrNull { hypot(it.first - x, it.second - z) }
                ?.takeIf { hypot(it.first - x, it.second - z) <= LOOSE_RANGE } ?: return null
            tx = near.first; tz = near.second
        }
        val y = zones.point(if (modeId == "carry-tnt") TNT_SPAWN else Regions.bank(ownTeam, zones) ?: return null)?.second ?: return null
        val s = Step("loose", tx, y, tz, 1.5, "recover")
        if (pickedUp(s, now, x, z) { lostPending = false; if (modeId == "carry-tnt") holdingTnt = true else carried = 1 }) return step(now, x, z)
        return s
    }

    /** The step for a bot at ([x], [z]) at time [now]; null when the mode has no plan or the stage lacks the regions. */
    fun step(now: Long, x: Double, z: Double): Step? {
        syncFromFacts()
        val s = when (modeId) {
            "carry-tnt" -> (if (holdingTnt) null else recover(now, x, z)) ?: carryTnt(now, x, z)
            "carry-mini-tnt" -> miniTnt(now, x, z)
            "steal-jewelry" -> (if (carried > 0) null else recover(now, x, z)) ?: jewelry(now, x, z)
            "capture-wool" -> wool()
            "annihilation" -> annihilation(x, z)
            else -> null
        }
        role = s?.role
        lastEngage = s?.engage ?: Double.MAX_VALUE
        return s
    }

    /** Hands and TNT as the mode itself reports them (when the facts know), instead of what the pickups inferred. */
    private fun syncFromFacts() {
        when (modeId) {
            "carry-tnt" -> facts.tnt()?.let { holdingTnt = it.carrierName == botName }
            "carry-mini-tnt", "steal-jewelry" -> facts.carriedBy(botName)?.let { carried = it }
        }
    }

    private fun assigner() = RoleAssigner(ownTeam, botName, roster)

    private fun step(zone: String, kind: String, arrive: Double, role: Role? = null, engage: Double = Double.MAX_VALUE): Step? =
        zones.point(zone)?.let { Step(zone, it.first, it.second, it.third, arrive, kind, role, engage) }

    private fun at(x: Double, y: Double, z: Double, zone: String, kind: String, arrive: Double, role: Role, engage: Double = Double.MAX_VALUE) =
        Step(zone, x, y, z, arrive, kind, role, engage)

    private fun reached(s: Step, x: Double, z: Double) = hypot(s.x - x, s.z - z) <= s.arrive

    private fun nearest(candidates: List<String>, x: Double, z: Double): String? =
        candidates.mapNotNull { n -> zones.point(n)?.let { n to hypot(it.first - x, it.third - z) } }.minByOrNull { it.second }?.first

    private fun nearestEnemy(names: (String) -> String?, x: Double, z: Double) = nearest(enemyTeams.mapNotNull(names), x, z)

    /** True once the bot has stood inside [s]'s radius for [holdMs]: the modes' pickups and deliveries are short holds, not touches. */
    private fun held(s: Step, now: Long, x: Double, z: Double, holdMs: Long = HOLD_MS): Boolean {
        if (!reached(s, x, z)) { dwellSince = 0L; return false }
        if (dwellSince == 0L) dwellSince = now
        if (now - dwellSince < holdMs) return false
        dwellSince = 0L
        return true
    }

    // ---- carry-tnt (Rainmaker) --------------------------------------------------------------------------------------

    private fun carryTnt(now: Long, x: Double, z: Double): Step? {
        val spawn = zones.point(TNT_SPAWN) ?: return null
        val tnt = facts.tnt()
        val onGround = tnt != null && tnt.carrierTeam == null
        val tx = if (onGround) tnt!!.x else spawn.first
        val tz = if (onGround) tnt!!.z else spawn.third
        val a = if (holdingTnt) Assignment(Role.CARRIER) else assigner().carryTnt(facts, holdingTnt, tx, tz)
        return when (a.role) {
            Role.CARRIER -> {
                if (holdingTnt) route(now, x, z)
                else {
                    val s = at(tx, spawn.second, tz, TNT_SPAWN, "tnt", 1.5, Role.CARRIER, engage = CARRY_ENGAGE)
                    if (pickedUp(s, now, x, z) { holdingTnt = true; secured.clear() }) carryTnt(now, x, z) else s
                }
            }
            Role.SHIELD_BREAKER -> step(TNT_SPAWN, "shield", 2.0, Role.SHIELD_BREAKER, engage = GUARD_ENGAGE)
            Role.ESCORT -> {
                val mate = roster.firstOrNull { it.name == a.subject }?.takeIf { it.known() }
                val ex = mate?.x ?: tnt?.takeIf { it.carrierTeam == ownTeam }?.x
                val ez = mate?.z ?: tnt?.takeIf { it.carrierTeam == ownTeam }?.z
                if (ex != null && ez != null) at(ex, spawn.second, ez, "carrier", "escort", ESCORT_RADIUS, Role.ESCORT, ESCORT_ENGAGE)
                else step(TNT_SPAWN, "escort", 4.0, Role.ESCORT, ESCORT_ENGAGE)
            }
            Role.INTERCEPTOR -> {
                val c = tnt?.takeIf { it.carrierTeam != null && it.carrierTeam != ownTeam }
                val seen = facts.enemies().minByOrNull { hypot(it.x - x, it.z - z) }
                when {
                    c != null -> at(c.x, spawn.second, c.z, "enemy-carrier", "intercept", 2.5, Role.INTERCEPTOR)
                    seen != null -> at(seen.x, spawn.second, seen.z, "enemy", "intercept", 2.5, Role.INTERCEPTOR)
                    else -> Regions.goal(ownTeam, zones)?.let { step(it, "guard", GUARD_RADIUS, Role.INTERCEPTOR, GUARD_ENGAGE) }
                }
            }
            else -> Regions.goal(ownTeam, zones)?.let { step(it, "guard", GUARD_RADIUS, Role.GOAL_GUARD, GUARD_ENGAGE) }
        }
    }

    /**
     * The carrier's walk (Rainmaker): every checkpoint on the way is a hold (the mode re-shields the carrier there), the last
     * stop is the enemy `deliver-<team>` pedestal, whose hold is the knockout. With the mode's own waypoint in the facts the
     * walk follows it (checkpoints the team already passed are not walked again, even by a new carrier); otherwise the bot
     * remembers the checkpoints it secured itself.
     */
    private fun route(now: Long, x: Double, z: Double): Step? {
        facts.nextWaypoint(ownTeam)?.let { w ->
            val s = Step(if (w.goal) "goal" else "waypoint", w.x, zones.point(TNT_SPAWN)?.second ?: 65.0, w.z,
                if (w.goal) BASE_RADIUS else CHECKPOINT_RADIUS, if (w.goal) "deliver" else "checkpoint", Role.CARRIER, CARRY_ENGAGE)
            if (held(s, now, x, z, if (w.goal) DELIVER_HOLD_MS else CHECKPOINT_HOLD_MS) && w.goal) { holdingTnt = false; return carryTnt(now, x, z) }
            return s
        }
        val enemy = enemyTeams.filter { Regions.goal(it, zones) != null }
            .minByOrNull { t -> zones.point(Regions.goal(t, zones)!!)!!.let { hypot(it.first - x, it.third - z) } } ?: return null
        val goalName = Regions.goal(enemy, zones) ?: return null
        val goal = zones.point(goalName) ?: return null
        val myDist = hypot(goal.first - x, goal.third - z)
        val ahead = Regions.checkpoints(enemy, zones).filter { it !in secured }.firstOrNull { cp ->
            zones.point(cp)?.let { hypot(goal.first - it.first, goal.third - it.third) < myDist - CHECKPOINT_RADIUS } == true
        }
        if (ahead != null) {
            val s = step(ahead, "checkpoint", CHECKPOINT_RADIUS, Role.CARRIER, CARRY_ENGAGE) ?: return null
            if (held(s, now, x, z, CHECKPOINT_HOLD_MS)) { secured += ahead; return route(now, x, z) }
            return s
        }
        val s = step(goalName, "deliver", BASE_RADIUS, Role.CARRIER, CARRY_ENGAGE) ?: return null
        if (held(s, now, x, z, DELIVER_HOLD_MS)) { holdingTnt = false; secured.clear(); return carryTnt(now, x, z) }
        return s
    }

    // ---- carry-mini-tnt (Clam Blitz) -------------------------------------------------------------------------------

    private fun miniTnt(now: Long, x: Double, z: Double): Step? {
        val a = assigner().carryMiniTnt(facts, enemyTeams, carried, holdsPower = false)
        return when (a.role) {
            Role.BASKET_GUARD -> {
                val power = facts.powerTnts().firstOrNull { it.team != ownTeam }
                if (power != null) at(power.x, zones.point(Regions.basket(ownTeam, zones) ?: "")?.second ?: 65.0, power.z, "power-tnt", "intercept", 3.0, Role.BASKET_GUARD)
                else Regions.basket(ownTeam, zones)?.let { step(it, "guard", GUARD_RADIUS, Role.BASKET_GUARD, GUARD_ENGAGE) }
            }
            // The power TNT is thrown INTO the enemy basket: that is what opens its barrier, so it never waits for it.
            Role.THROWER -> deliverClams(now, x, z, "throw", Role.THROWER, waitForBarrier = false)
            Role.RUSHER -> deliverClams(now, x, z, "deliver", Role.RUSHER)
            else -> collect(now, x, z)
        }
    }

    /** Carries what the hands hold into the nearest enemy basket; minis wait outside while its barrier is shut, a power TNT does not. */
    private fun deliverClams(now: Long, x: Double, z: Double, kind: String, role: Role, waitForBarrier: Boolean = true): Step? {
        val basket = nearestEnemy({ Regions.basket(it, zones) }, x, z) ?: return null
        val team = enemyTeams.first { Regions.basket(it, zones) == basket }
        if (waitForBarrier && facts.barrierOpen(team) == false) return step(basket, "stage", BARRIER_WAIT, role)
        val s = step(basket, kind, BASE_RADIUS, role) ?: return null
        if (held(s, now, x, z)) { if (kind != "throw") carried = 0; return miniTnt(now, x, z) }
        return s
    }

    private fun collect(now: Long, x: Double, z: Double): Step? {
        val stage = Regions.clams(zones).mapNotNull { n -> zones.point(n)?.let { ClamFact(n, it.first, it.third) } }
        val lying = facts.clams() ?: stage.filter { now - (takenAt[it.id] ?: -pickupCooldownMs) >= pickupCooldownMs }
        // Hands full (or nothing left to take): a shut barrier means waiting at the own basket until a power TNT opens one.
        if (carried >= carryLimit || (carried > 0 && lying.isEmpty())) {
            val open = enemyTeams.any { facts.barrierOpen(it) == true }
            if (open || facts.barrierOpen(enemyTeams.firstOrNull() ?: "") == null) return deliverClams(now, x, z, "deliver", Role.COLLECTOR)
            return Regions.basket(ownTeam, zones)?.let { step(it, "stage", BARRIER_WAIT, Role.COLLECTOR) }
        }
        val target = claimed(lying, x, z) ?: stage.minByOrNull { hypot(it.x - x, it.z - z) } ?: return null
        val y = zones.point(target.id)?.second ?: zones.point(Regions.clams(zones).firstOrNull() ?: "")?.second ?: 65.0
        val s = Step(target.id, target.x, y, target.z, 1.5, "pickup", Role.COLLECTOR)
        if (target in lying && pickedUp(s, now, x, z) { takenAt[target.id] = now; carried = facts.carriedBy(botName) ?: (carried + 1) }) return miniTnt(now, x, z)
        return s
    }

    /** Greedy split of the lying clams: the mates in name order each take their nearest one, so two bots do not run to the same clam. */
    private fun claimed(lying: List<ClamFact>, x: Double, z: Double): ClamFact? {
        if (lying.isEmpty()) return null
        val remaining = lying.toMutableList()
        val team = (roster.filter { it.alive && it.name != botName } + Mate(botName, x, z)).sortedBy { it.name }
        for (m in team) {
            val (mx, mz) = if (m.name == botName) x to z else m.x to m.z
            if (m.name != botName && !m.known()) continue
            val best = remaining.minByOrNull { hypot(it.x - mx, it.z - mz) } ?: return null
            if (m.name == botName) return best
            remaining -= best
        }
        return null
    }

    // ---- steal-jewelry -----------------------------------------------------------------------------------------------

    private fun jewelry(now: Long, x: Double, z: Double): Step? {
        if (carried > 0) {
            val bank = Regions.bank(ownTeam, zones) ?: return null
            val s = step(bank, "bank", BASE_RADIUS, Role.THIEF) ?: return null
            if (held(s, now, x, z, BANK_HOLD_MS)) { carried = 0; return jewelry(now, x, z) }
            return s
        }
        val own = "jewelry-$ownTeam"
        val ownPoint = zones.point(own)
        val threatened = ownPoint?.let { p -> facts.enemies().any { hypot(it.x - p.first, it.z - p.third) <= THREAT_RANGE } } == true
        val thieves = facts.jewelCarriers().filter { it.team != ownTeam }
        val a = assigner().stealJewelry(threatened, thiefLoose = thieves.isNotEmpty())
        if (a.role == Role.PEDESTAL_GUARD) {
            val y = ownPoint?.second ?: 65.0
            // A thief with a jewel is chased wherever it is: killing it drops the jewel before it banks.
            thieves.minByOrNull { hypot(it.x - x, it.z - z) }?.let { return at(it.x, y, it.z, "thief", "intercept", 2.5, Role.PEDESTAL_GUARD) }
            val near = facts.enemies().minByOrNull { ownPoint?.let { p -> hypot(it.x - p.first, it.z - p.third) } ?: Double.MAX_VALUE }
            if (near != null && threatened) return at(near.x, y, near.z, "thief", "intercept", 2.5, Role.PEDESTAL_GUARD)
            return step(own, "guard", GUARD_RADIUS, Role.PEDESTAL_GUARD, GUARD_ENGAGE)
        }
        val stocked = enemyTeams.filter { facts.jewelsOn(it)?.let { n -> n > 0 } != false }
        val zone = nearestEnemy({ t -> "jewelry-$t".takeIf { t in stocked } }, x, z) ?: return null
        val s = step(zone, "jewel", 1.5, Role.THIEF) ?: return null
        if (pickedUp(s, now, x, z, STEAL_HOLD_MS) { carried = facts.carriedBy(botName) ?: 1 }) return jewelry(now, x, z)
        return s
    }

    // ---- capture-wool (Splat Zones) ----------------------------------------------------------------------------------

    private fun wool(): Step? {
        val names = zones.names("wool-")
        for (n in names) {
            val owner = facts.zoneOwner(n)
            if (woolOwner[n] == ownTeam && owner != ownTeam) woolLost += n
            if (owner == ownTeam) woolLost -= n
            woolOwner[n] = owner
        }
        val a = assigner().captureWool(names, facts, woolLost) ?: return null
        val zone = a.zone ?: return null
        return step(zone, "wool", 2.0, a.role, if (a.role == Role.HOLDER) HOLD_ENGAGE else Double.MAX_VALUE)
    }

    // ---- annihilation ------------------------------------------------------------------------------------------------

    private fun annihilation(x: Double, z: Double): Step? {
        val all = zones.pillars()
        if (all.isEmpty()) return null
        fun standing(p: PillarSite) = facts.pillarStanding(p.team, p.tier) ?: true
        val mine = all.filter { it.team == ownTeam && standing(it) }.sortedBy { it.tier }
        val threatened = facts.enemies().any { e -> mine.any { hypot(e.x - (it.x + 0.5), e.z - (it.z + 0.5)) <= THREAT_RANGE } }
        val a = assigner().annihilation(threatened)
        if (a.role == Role.PILLAR_DEFENDER) {
            val p = mine.firstOrNull() ?: return null
            return Step("pillar-$ownTeam-${p.tier}", p.x + 0.5, p.y.toDouble(), p.z + 0.5, GUARD_RADIUS, "guard", Role.PILLAR_DEFENDER, GUARD_ENGAGE)
        }
        val target = RoleAssigner.weakestTeam(enemyTeams) { t -> all.count { it.team == t && standing(it) } } ?: return null
        val p = all.filter { it.team == target && standing(it) }.minByOrNull { it.tier } ?: return null
        return Step("pillar-$target-${p.tier}", p.x + 0.5, p.y.toDouble(), p.z + 0.5, PILLAR_REACH, "pillar", Role.PILLAR_ATTACKER,
            engage = ATTACK_ENGAGE, strike = Triple(p.x, p.y, p.z), affinity = p.toolClass())
    }

    /** Stage region ids per the objective-mode region contract (ADR-0296): no fallbacks, a stage without them has no plan. */
    private object Regions {
        fun goal(team: String, z: Zones) = "deliver-$team".takeIf { z.point(it) != null }
        fun basket(team: String, z: Zones) = "basket-$team".takeIf { z.point(it) != null }
        fun bank(team: String, z: Zones) = "base-$team".takeIf { z.point(it) != null }
        fun clams(z: Zones) = z.names("clam-")
        fun checkpoints(enemy: String, z: Zones) = z.names("checkpoint-$enemy-").sortedBy { it.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE }
    }

    companion object {
        val MODES = setOf("carry-tnt", "carry-mini-tnt", "steal-jewelry", "capture-wool", "annihilation")
        const val TNT_SPAWN = "tnt-spawn"
        const val BASE_RADIUS = 3.0
        const val HOLD_MS = 1_500L
        /** Server holds plus a margin: checkpoint 3 s, deliver 2 s, steal 4 s, bank 0.75 s. */
        const val CHECKPOINT_HOLD_MS = 3_800L
        const val DELIVER_HOLD_MS = 2_800L
        const val STEAL_HOLD_MS = 4_500L
        const val BANK_HOLD_MS = 1_500L
        const val CONFIRM_MS = 4_000L
        /** Custom name the mode puts on loose (dropped) objective markers; keep equal to `PaperObjectiveStage.LOOSE_TAG`. */
        const val LOOSE_TAG = "astera:objective-loose"
        const val RECOVER_MS = 25_000L
        const val LOOSE_RANGE = 40.0
        const val CHECKPOINT_RADIUS = 2.0
        const val GUARD_RADIUS = 5.0
        const val ESCORT_RADIUS = 4.0
        const val BARRIER_WAIT = 14.0
        const val PILLAR_REACH = 3.5
        const val THREAT_RANGE = 30.0
        /** How far away an enemy may be for the brain to leave the step: a TNT carrier only fights what is on top of it. */
        const val CARRY_ENGAGE = 6.0
        const val ESCORT_ENGAGE = 25.0
        const val GUARD_ENGAGE = 30.0
        const val HOLD_ENGAGE = 14.0
        /** A pillar attacker only fights an enemy this close; farther ones are walked past so the pillar trip finishes. */
        const val ATTACK_ENGAGE = 12.0
    }
}
