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
    /**
     * Key of the running match, equal for the whole squad (stage slot + start bucket), and the scoreboard team of a player name
     * (null = unknown). They only feed [TieBreak]: which of several equally good enemy teams a bot burns first.
     */
    private val matchKey: String = "",
    private val teamOf: (String) -> String? = { null },
) {
    /** Stage regions by name (world coordinates). */
    interface Zones {
        fun point(name: String): Triple<Double, Double, Double>?
        fun names(prefix: String): List<String>

        /** Annihilation pillar sites (block coordinates) of every team. */
        fun pillars(): List<PillarSite> = emptyList()

        /** Respawn points of every team as (team, x, z); empty when the stage has none. */
        fun spawns(): List<Triple<String, Double, Double>> = emptyList()
    }

    /** Spawn zone of [team]: centroid of its respawn points and the radius that covers them plus [SPAWN_ZONE_MARGIN] (derived from the stage). */
    private val spawnZones: Map<String, Triple<Double, Double, Double>> by lazy {
        zones.spawns().groupBy { it.first }.mapValues { (_, l) ->
            val cx = l.sumOf { it.second } / l.size
            val cz = l.sumOf { it.third } / l.size
            Triple(cx, cz, l.maxOf { hypot(it.second - cx, it.third - cz) } + SPAWN_ZONE_MARGIN)
        }
    }

    /** True when (x, z) lies inside the spawn zone of [team]. */
    fun inSpawnZone(team: String?, x: Double, z: Double): Boolean =
        team != null && spawnZones[team]?.let { hypot(x - it.first, z - it.second) <= it.third } == true

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
    private fun pickedUp(s: Step, now: Long, x: Double, z: Double, holdMs: Long = HOLD_MS, click: Boolean = false, apply: () -> Unit): Boolean {
        if (awaitingSince != 0L) {
            if (confirmed > seenConfirmed) {
                seenConfirmed = confirmed; awaitingSince = 0L; clickWanted = false; apply(); return true
            }
            if (now - awaitingSince > CONFIRM_MS) { awaitingSince = 0L; clickWanted = false }
            return false
        }
        // A right-click pickup needs no dwell: standing within reach of the marker is enough, then the brain clicks it.
        if (click) {
            if (reached(s, x, z)) { awaitingSince = now; clickWanted = true }
        } else if (held(s, now, x, z, holdMs)) awaitingSince = now
        return false
    }

    /** True while the bot stands at a right-click pickup and waits for the server's confirm: the brain clicks the nearest interaction entity. */
    var clickWanted: Boolean = false; private set

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
        if (pickedUp(s, now, x, z, click = modeId == "carry-tnt") { lostPending = false; if (modeId == "carry-tnt") holdingTnt = true else carried = 1 }) return step(now, x, z)
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
            "annihilation" -> annihilation(now, x, z)
            else -> null
        }
        role = s?.role
        lastEngage = s?.engage ?: if (preparing(now)) ATTACK_ENGAGE_FAR else Double.MAX_VALUE
        return s
    }

    /** Hands and TNT as the mode itself reports them (when the facts know), instead of what the pickups inferred. */
    private fun syncFromFacts() {
        when (modeId) {
            "carry-tnt" -> facts.tnt()?.let {
                // A carrier whose name is unknown (client view) leaves the pickup-confirmed state alone while the own team carries.
                holdingTnt = if (it.carrierName != null) it.carrierName == botName else it.carrierTeam == ownTeam && holdingTnt
            }
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
                    if (pickedUp(s, now, x, z, click = true) { holdingTnt = true; secured.clear() }) carryTnt(now, x, z) else s
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
        // A checkpoint the bot stands on is still ahead: without that, arriving at it (within CHECKPOINT_RADIUS) drops it from the
        // list before the hold ever runs, the checkpoint is never secured and the server refuses the delivery.
        val ahead = Regions.checkpoints(enemy, zones).filter { it !in secured }.firstOrNull { cp ->
            zones.point(cp)?.let { hypot(it.first - x, it.third - z) <= CHECKPOINT_RADIUS || hypot(goal.first - it.first, goal.third - it.third) < myDist - CHECKPOINT_RADIUS } == true
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
        if (target in lying && pickedUp(s, now, x, z, click = true) { takenAt[target.id] = now; carried = facts.carriedBy(botName) ?: (carried + 1) }) return miniTnt(now, x, z)
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

    // Own-pillar watch (role switching) and engage / chase state.
    private val pillarHpSeen = HashMap<Int, Int>()
    private var pillarHitAt = NEVER
    private var pillarHit: PillarSite? = null
    private var lastDamagedAt = NEVER
    private var farFightSince = NEVER
    private var farBlockedUntil = 0L
    private var lastSeenX = Double.NaN
    private var lastSeenZ = Double.NaN
    private var lastSeenAt = NEVER
    private var chaseSince = NEVER
    private var chaseOriginX = 0.0
    private var chaseOriginZ = 0.0
    private var chaseBlockedUntil = 0L
    private var objectiveX = Double.NaN
    private var objectiveZ = Double.NaN

    /** The bot took damage at [now] (from whom is not known): an enemy up to [ATTACK_ENGAGE_FAR] away then counts as a threat for [DAMAGED_MEMORY_MS]. */
    fun noteDamaged(now: Long) { lastDamagedAt = now }

    /** The team that most recently damaged us (a player of it hit the bot, or stood at our pillar when it lost HP). */
    private val damagedByAt = HashMap<String, Long>()

    /** The bot was hurt by a player of [team] (null/own = unknown): ties between equal enemy teams go to that team first. */
    fun noteAttackerTeam(now: Long, team: String?) { if (team != null && team != ownTeam) damagedByAt[team] = now }

    /** Seeds of the tie-break numbers: a squad shares [teamSeed] (so P3+ focus agrees), [botSeed] is the bot's own stream. */
    private val teamSeed = TieBreak.seed(matchKey, ownTeam)
    private val botSeed = TieBreak.seed(matchKey, ownTeam, botName)

    /** The enemy team this bot burns now (kept while it stays among the weakest). */
    private var committed: String? = null
    /** The enemy team the squad focuses on (P3+), for traces and scenarios. */
    var focusTeam: String? = null; private set

    /**
     * Where the bot's target was last seen (the target memory of the fight policy), at [seenAt]. An attacker whose enemy
     * dropped out of sight chases to that spot for at most [CHASE_MS] and [CHASE_LEASH] blocks from where the chase began.
     */
    fun noteLastKnown(x: Double, z: Double, seenAt: Long) { lastSeenX = x; lastSeenZ = z; lastSeenAt = seenAt }

    /** Whether the own pillar was hit within the last [DEFEND_QUIET_MS] (hysteresis for the attacker -> defender switch). */
    fun pillarUnderAttack(now: Long): Boolean = pillarHitAt != NEVER && now - pillarHitAt in 0..DEFEND_QUIET_MS

    /**
     * Whether the brain should leave the current step and fight [enemy] (call once per tick with the nearest one). Inside the
     * step's [engage] radius always; a pillar attacker also takes a threat (it was damaged lately, or the enemy stands within
     * [PATH_THREAT_RADIUS] of its way to the objective) or an easy kill (hp fraction <= [EASY_KILL_HP]) up to [ATTACK_ENGAGE_FAR].
     * Such a far fight is leashed: it ends after [FAR_FIGHT_MS] or when the bot is [OBJECTIVE_LEASH] blocks from the objective, and
     * no new far fight starts for [FAR_COOLDOWN_MS] (no flip-flop between walking and fighting).
     */
    fun engages(enemy: Sighting, now: Long, x: Double, z: Double): Boolean {
        // Preparation phase: an enemy inside an enemy base is not worth following (nothing to win there); one in the field is fought as usual.
        if (offLimits(now, enemy.x, enemy.z) && !offLimits(now, x, z)) { farFightSince = NEVER; return false }
        val d = hypot(enemy.x - x, enemy.z - z)
        // Next to the pillar and nobody is hitting us: dig. A defended base otherwise pulls the attacker into a perpetual fight with
        // respawning defenders (0 pillar damage in 2,300 player-seconds); being hit, or an enemy within melee range, still fights.
        val hit = lastDamagedAt != NEVER && now - lastDamagedAt in 0..DAMAGED_MEMORY_MS
        // No spawn camping: a defender still inside its own spawn zone is left alone (pillar damage first) until it leaves or hits us.
        if (role == Role.PILLAR_ATTACKER && !hit && inSpawnZone(enemy.name?.let(teamOf), enemy.x, enemy.z)) { farFightSince = NEVER; return false }
        if (role == Role.PILLAR_ATTACKER && !objectiveX.isNaN() && !hit && d > DIG_MELEE_RADIUS &&
            hypot(objectiveX - x, objectiveZ - z) <= DIG_FOCUS_RADIUS) { farFightSince = NEVER; return false }
        if (d <= lastEngage) { farFightSince = NEVER; return true }
        if (role != Role.PILLAR_ATTACKER || d > ATTACK_ENGAGE_FAR || now < farBlockedUntil) { farFightSince = NEVER; return false }
        val damaged = lastDamagedAt != NEVER && now - lastDamagedAt in 0..DAMAGED_MEMORY_MS
        val easy = enemy.hpFraction?.let { it <= EASY_KILL_HP } == true
        val onPath = !objectiveX.isNaN() && distToSegment(enemy.x, enemy.z, x, z, objectiveX, objectiveZ) <= PATH_THREAT_RADIUS
        if (!(damaged || easy || onPath)) { farFightSince = NEVER; return false }
        if (farFightSince == NEVER) farFightSince = now
        val leashed = !objectiveX.isNaN() && hypot(objectiveX - x, objectiveZ - z) > OBJECTIVE_LEASH
        if (now - farFightSince > FAR_FIGHT_MS || leashed) {
            farFightSince = NEVER; farBlockedUntil = now + FAR_COOLDOWN_MS; return false
        }
        return true
    }

    private fun distToSegment(px: Double, pz: Double, ax: Double, az: Double, bx: Double, bz: Double): Double {
        val dx = bx - ax; val dz = bz - az
        val len2 = dx * dx + dz * dz
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (pz - az) * dz) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), pz - (az + t * dz))
    }

    /** Reads the own pillars' HP; a drop marks the pillar as hit at [now]. */
    private fun watchOwnPillars(now: Long, own: List<PillarSite>) {
        for (p in own) {
            val hp = facts.pillarHp(ownTeam, p.tier)?.hp ?: continue
            val before = pillarHpSeen.put(p.tier, hp)
            if (before != null && hp < before) {
                pillarHitAt = now; pillarHit = p
                // Who is at the pillar: the nearest enemy in sight within PILLAR_ATTACKER_RADIUS of it.
                facts.enemies().filter { hypot(it.x - (p.x + 0.5), it.z - (p.z + 0.5)) <= PILLAR_ATTACKER_RADIUS }
                    .minByOrNull { hypot(it.x - (p.x + 0.5), it.z - (p.z + 0.5)) }
                    ?.name?.let { teamOf(it) }?.let { noteAttackerTeam(now, it) }
            }
        }
    }

    private var firstAskedAt = NEVER
    private var phaseSeen = 0

    /**
     * Annihilation Preparation phase (pillars immune): [ModeFacts.phase] says which phase runs (the boss bar colour). The
     * [BUILD_PHASE_MS] timer is used ONLY while no bar has been seen at all (facts return null); a bar that cannot be read makes
     * [ModeFacts.phase] throw, so there is no silent fallback to the timer. The highest phase seen sticks.
     */
    fun preparing(now: Long): Boolean {
        if (modeId != "annihilation") return false
        if (firstAskedAt == NEVER) firstAskedAt = now
        facts.phase()?.let { phaseSeen = maxOf(phaseSeen, it) }
        return if (phaseSeen > 0) phaseSeen == 1 else now - firstAskedAt < BUILD_PHASE_MS
    }

    /** Preparation phase and ([x], [z]) lies within [BASE_AVOID_RADIUS] of an enemy pillar: bots keep out (no pillar to break there yet). */
    fun offLimits(now: Long, x: Double, z: Double): Boolean =
        preparing(now) && zones.pillars().any { it.team != ownTeam && hypot(it.x + 0.5 - x, it.z + 0.5 - z) <= BASE_AVOID_RADIUS }

    /** A chase step to the target's last known spot, or null when there is nothing (any more) to chase. */
    private fun chase(now: Long, x: Double, z: Double): Step? {
        if (lastSeenAt != NEVER && offLimits(now, lastSeenX, lastSeenZ)) { chaseSince = NEVER; lastSeenAt = NEVER; return null }
        if (lastSeenAt == NEVER || now < chaseBlockedUntil || now - lastSeenAt > CHASE_MEMORY_MS) { chaseSince = NEVER; return null }
        if (chaseSince == NEVER) { chaseSince = now; chaseOriginX = x; chaseOriginZ = z }
        val arrived = hypot(lastSeenX - x, lastSeenZ - z) <= CHASE_ARRIVE
        val leashed = hypot(x - chaseOriginX, z - chaseOriginZ) > CHASE_LEASH
        if (arrived || leashed || now - chaseSince > CHASE_MS) {
            chaseSince = NEVER; chaseBlockedUntil = now + CHASE_COOLDOWN_MS; lastSeenAt = NEVER; return null
        }
        return Step("last-known", lastSeenX, zones.point(TNT_SPAWN)?.second ?: 65.0, lastSeenZ, CHASE_ARRIVE, "chase", Role.PILLAR_ATTACKER, engage = ATTACK_ENGAGE_FAR)
    }

    private fun annihilation(now: Long, x: Double, z: Double): Step? {
        val all = zones.pillars()
        if (all.isEmpty()) return null
        fun standing(p: PillarSite) = facts.pillarStanding(p.team, p.tier) ?: true
        val mine = all.filter { it.team == ownTeam && standing(it) }.sortedBy { it.tier }
        watchOwnPillars(now, mine)
        val threatened = facts.enemies().any { e -> mine.any { hypot(e.x - (it.x + 0.5), e.z - (it.z + 0.5)) <= THREAT_RANGE } }
        val underAttack = pillarUnderAttack(now)
        preparing(now) // advances phaseSeen
        // From phase 3 the whole squad commits to one enemy team and its Nexus; defenders stay home only while the own pillars are at risk.
        val focus = phaseSeen >= FOCUS_PHASE
        val home = (if (underAttack) pillarHit?.takeIf { h -> mine.any { it.tier == h.tier } } else null) ?: mine.firstOrNull()
        val a = when {
            focus && !threatened && !underAttack -> Assignment(Role.PILLAR_ATTACKER)
            underAttack && home != null ->
                assigner().annihilationUnderAttack(threatened, home.x + 0.5, home.z + 0.5, x, z, sticky = role == Role.PILLAR_DEFENDER)
            else -> assigner().annihilation(threatened)
        }
        if (a.role == Role.PILLAR_DEFENDER) {
            val p = home ?: return null
            // Switched defenders run to the hit pillar and fight on top of it; the regular guard keeps its post radius.
            return Step("pillar-$ownTeam-${p.tier}", p.x + 0.5, p.y.toDouble(), p.z + 0.5, GUARD_RADIUS, "guard", Role.PILLAR_DEFENDER, GUARD_ENGAGE)
        }
        // Preparation: attackers gather (ore, stars, caches, shop, norma) until the pillars open; the brain falls through to those.
        if (preparing(now)) return null
        fun count(t: String) = all.count { it.team == t && standing(it) }
        // Eliminated: no pillar left, or the Nexus (tier 3) is gone.
        fun eliminated(t: String) = count(t) == 0 || all.any { it.team == t && it.tier == NEXUS_TIER && !standing(it) }
        // P1-2: every bot breaks a tie by who hurt it last, then by its own seeded stream. P3+: the squad must agree, so only the
        // squad-shared seed decides (what a bot was hit by is its own business).
        val pick = RoleAssigner.weakestTeam(
            enemyTeams, ::count, ::eliminated,
            lastHitAt = { t -> if (focus) null else damagedByAt[t] },
            priority = { t -> TieBreak.priority(if (focus) teamSeed else botSeed, t) },
        )
        val cur = committed
        val target = (if (cur != null && pick != null && !eliminated(cur) && count(cur) <= count(pick)) cur else pick) ?: return null
        committed = target
        if (focus) focusTeam = target
        // Phase 3+: the Nexus first (losing it eliminates the team); before that the outermost standing pillar.
        val theirs = all.filter { it.team == target && standing(it) }
        val p = (if (focus) theirs.maxByOrNull { it.tier } else theirs.minByOrNull { it.tier }) ?: return null
        objectiveX = p.x + 0.5; objectiveZ = p.z + 0.5
        chase(now, x, z)?.let { return it }
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
        /** "Not set" for the time stamps above (0 is a valid clock value). */
        private const val NEVER = Long.MIN_VALUE
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
        /** Annihilation phase from which the squad focuses one enemy team (Center: Tier 2 + 3 vulnerable). */
        const val FOCUS_PHASE = 3

        /** Within this many blocks of the target pillar an unhurt attacker digs instead of fighting; an enemy this close still gets hit. */
        const val DIG_FOCUS_RADIUS = 15.0
        const val DIG_MELEE_RADIUS = 4.0

        /** Blocks added to the spread of a team's respawn points to get its spawn zone. */
        const val SPAWN_ZONE_MARGIN = 12.0
        const val NEXUS_TIER = 3
        /** An enemy this close to an own pillar that just lost HP is taken as the one who hit it. */
        const val PILLAR_ATTACKER_RADIUS = 20.0
        const val THREAT_RANGE = 30.0
        /** How far away an enemy may be for the brain to leave the step: a TNT carrier only fights what is on top of it. */
        const val CARRY_ENGAGE = 6.0
        const val ESCORT_ENGAGE = 25.0
        const val GUARD_ENGAGE = 30.0
        const val HOLD_ENGAGE = 14.0
        /** A pillar attacker only fights an enemy this close; farther ones are walked past so the pillar trip finishes. */
        const val ATTACK_ENGAGE = 12.0
        /** A pillar attacker also takes a threatening or easy enemy this far away. */
        const val ATTACK_ENGAGE_FAR = 30.0
        /** Enemy hp fraction at or below which it is an easy kill. */
        const val EASY_KILL_HP = 0.4
        /** An enemy this close to the straight way to the objective is a threat to the trip. */
        const val PATH_THREAT_RADIUS = 8.0
        /** How long a damage taken keeps far enemies threatening. */
        const val DAMAGED_MEMORY_MS = 5_000L
        /** A far fight ends after this long, or this far from the objective, and does not restart for [FAR_COOLDOWN_MS]. */
        const val FAR_FIGHT_MS = 12_000L
        const val FAR_COOLDOWN_MS = 6_000L
        const val OBJECTIVE_LEASH = 80.0

        /** Preparation phase: blocks around every enemy pillar (tier 1 sits ~25 from the spawn) that bots do not enter. */
        const val BASE_AVOID_RADIUS = 35.0

        /** Timer fallback for hosts without a phase bar: the `annihilation.yaml` build phase, 7200 ticks. */
        const val BUILD_PHASE_MS = 360_000L
        /** Chase: remembered target spot lifetime, max duration, max distance from where it began, arrive radius, pause after. */
        const val CHASE_MEMORY_MS = 4_000L
        const val CHASE_MS = 8_000L
        const val CHASE_LEASH = 40.0
        const val CHASE_ARRIVE = 2.5
        const val CHASE_COOLDOWN_MS = 6_000L
        /** After an own-pillar hit, attackers stay switched to defend until this long without a further hit. */
        const val DEFEND_QUIET_MS = 20_000L
    }
}
