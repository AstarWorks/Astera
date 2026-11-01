package com.astarworks.astera.bots

import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How good the opponent is. The defaults are a beatable but non-trivial player (ADR-0247). */
data class BotSkill(
    /** Random aim error per attack, degrees (yaw and pitch). */
    val aimErrorDeg: Float = 4f,
    val swingIntervalMs: Long = 700,
    val shotIntervalMs: Long = 450,
    /** Distance a ranged bot tries to hold. */
    val preferredRange: Double = 9.0,
    /** Blocks per client tick while chasing (walk = 0.216, sprint = 0.28). */
    val walkSpeed: Double = 0.23,
)

enum class WeaponKind { MELEE, RANGED }

/** [magazine]: a ranged item with an ammo counter and a reload (a gun); a ranged item without one is a caster (staff). */
data class Weapon(val slot: Int, val kind: WeaponKind, val magazine: Boolean = false)

/** A point of interest the bot walks to when no enemy is around. */
data class Objective(val name: String, val x: Double, val y: Double, val z: Double)

/** Mode knowledge for the brain: where to go when nobody is in sight. Implemented by [StageObjectives]. */
fun interface ObjectiveSource {
    /** The objective for a bot that spawned at ([spawnX], [spawnZ]), or null for "just look for enemies". */
    fun objectiveFor(spawnX: Double, spawnZ: Double): Objective?

    /** Star generator pedestals (x, z) of the stage, where stars wait to be picked up. */
    fun starPoints(): List<Pair<Double, Double>> = emptyList()

    /** One exploration cache spot (ADR-0318): refills [respawnMs] after it was opened. Stage-local coordinates. */
    class CachePoint(val x: Double, val y: Double, val z: Double, val respawnMs: Long)

    /** Exploration caches of the stage; empty = none. */
    fun cachePoints(): List<CachePoint> = emptyList()

    fun routeGraph(): ArenaRouteGraph? = null

    /**
     * Server-side mode state for hosts that share a process with the server (game-sim): wraps [observed] (what the client sees)
     * with the mode's own query. [originX]/[originZ] shift stage-local snapshot coordinates into the world frame. Protocol
     * bots in e2e/live cannot reach the server and keep null.
     */
    fun modeFacts(observed: ModeFacts, originX: Double, originZ: Double): ModeFacts? = null

    /** Stage regions (stage-local coordinates) for [ModePlanner]; null = unknown. */
    fun zones(): ModePlanner.Zones? = null

    /** Team (`red`/`blue`) whose spawn is nearest ([spawnX], [spawnZ]) and every team of the stage. */
    fun ownTeamAt(spawnX: Double, spawnZ: Double): String? = null
    fun teams(): List<String> = emptyList()

    /** Stage-local shop, base ore generators and Mid ore nodes of [team] for the lull-time [EconomyPlanner]; null = the stage has no shop. */
    fun economySites(team: String): EconomyPlanner.Sites? = null

    /** Shopping list of the base shop in priority order (items and price per rung). */
    fun economyLadder(): List<EconomyPlanner.Companion.Buy> = emptyList()
}

/**
 * The combat AI of a protocol bot (ADR-0247). Every 50 ms (one client tick) it:
 *
 *  1. picks the nearest enemy player from the entity packets (teams from the scoreboard, never a team mate),
 *  2. walks towards / away from / around it, one position packet per tick, never over an island edge,
 *  3. faces it and attacks with the weapon that fits the distance: the melee item swings ([Bot.punch] +
 *     [Bot.attack]) while energy (the food bar) allows it, the ranged item fires ([Bot.useItem]) and is reloaded
 *     (Shift+Q) when the magazine reads empty on the action bar,
 *  4. with no enemy in sight, walks to the mode objective (enemy core ...) or the last place an enemy was seen,
 *     and roams otherwise,
 *  5. while dead (spectator death cam) does nothing, and presses Respawn if the server asks for it.
 *
 * It only acts while [mode] is [Mode.FIGHT]; the owner flips that when the bot is in a match.
 */
class BotBrain(
    private val bot: BotHost,
    private val skill: BotSkill = BotSkill(),
    private val objectives: ObjectiveSource? = null,
    /** Pick the Norma goal (`/astera norma choose star|kill`) from the situation. Off = keep whatever the server set. */
    private val chooseNormaGoal: Boolean = true,
    /** Game-mode id of the lane (lane id = mode id): selects the per-mode objective plan ([ModePlanner]). */
    private val modeId: String? = null,
    /** Aim-error / jitter source; the default seeds from the bot name (live bots), a sim passes its own. */
    private val rnd: Random = Random(bot.name.hashCode().toLong()),
    /** What extra skills each item casts, derived from the shipped content ([ItemSkillCatalog]); null = no content, no extra skills. */
    private val itemSkills: ItemSkillCatalog? = null,
) : AutoCloseable {

    enum class Mode { IDLE, FIGHT }

    private enum class Goal { STAR, KILL }

    private class Dest(val x: Double, val z: Double, val y: Double?, val arrive: Double, val kind: String, val starId: Int = -1, val zone: String = "", val strike: Triple<Int, Int, Int>? = null, val affinity: LoadoutClassifier.Affinity? = null)

    @Volatile var mode: Mode = Mode.IDLE

    /** Counters for reports and tests. */
    val swings = AtomicInteger()
    /** Healing potions drunk (right-click on the consumable slot while retreating at low HP). */
    val drinks = AtomicInteger()
    /** Health at the last drink and the highest health seen in the 3 s after it; lets a test assert that drinking healed. */
    @Volatile var lastDrinkHp = Float.NaN
    @Volatile var peakHpAfterDrink = Float.NaN
    private var healSlot = -1
    private var lastDrinkAt = 0L
    val shots = AtomicInteger()
    val reloads = AtomicInteger()
    val ticksWithEnemy = AtomicInteger()
    val blockedByEdge = AtomicInteger()
    val starsCollected = AtomicInteger()
    val edgeFalls = AtomicInteger()
    val nav = Navigator(bot.terrain, objectives?.routeGraph())

    /** Milliseconds from entering the fight until the first enemy was seen / the first attack was sent (-1 = not yet). */
    @Volatile var firstSightMs = -1L; private set
    @Volatile var firstAttackMs = -1L; private set

    @Volatile var loadout: List<Weapon> = emptyList(); private set

    /** Pickaxe / shovel / axe hotbar slots by class (right-click tool skill, digs pillars); never part of the weapon [loadout]. */
    private val toolSlots = java.util.concurrent.ConcurrentHashMap<LoadoutClassifier.Affinity, Int>()

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bot-brain-${bot.name}").also { it.isDaemon = true }
    }

    // --- per-brain state, touched only by the tick thread ---
    private var lastSwingAt = 0L
    private var lastShotAt = 0L
    private var lastSwitchAt = 0L
    private var lastRespawnAt = 0L
    private var lastIdleNudgeAt = 0L
    private var lastLoadoutScanAt = 0L
    private var lastLoadoutSignature = ""
    private var reloadStartedAt = 0L
    private var reloadBusyUntil = 0L
    private var strafeSign = 1
    private var strafeUntil = 0L
    private var wasFighting = false
    private var spawnX = 0.0
    private var spawnZ = 0.0
    private var spawnY = 0.0
    private var objective: Objective? = null
    private var lastEnemyAt = 0L
    private var lastEnemyX = 0.0
    private var lastEnemyZ = 0.0
    private var wanderHeading = 0.0
    private var wanderUntil = 0L
    private var progressX = 0.0
    private var progressZ = 0.0
    private var progressAt = 0L
    private var preferredSide = 1
    private var screensSeen = bot.screens.size
    private var fightStartedAt = 0L
    private var lastKind = "-"
    private var lastTraceAt = 0L
    private val trace = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var localBlockedAt = 0L
    private var retreating = false
    private var retreatSince = 0L
    private var goal = if (chooseNormaGoal) Goal.STAR else Goal.KILL
    private var sentGoal: Goal? = null
    private var lastGoalChangeAt = 0L
    private var lastGoalSentAt = 0L
    private var engagedAt = 0L
    private var patrolIndex = 0
    private var patrolHeld = 0L
    private var dwellUntil = 0L
    /** Stage-local generator points shifted to the slot the bot plays in (set at each spawn). */
    private var starPoints: List<Pair<Double, Double>> = emptyList()
    /** Cache spots shifted to the slot, and when each was last visited (a cache is empty until its respawn has passed). */
    private var cachePoints: List<ObjectiveSource.CachePoint> = emptyList()
    private val cacheVisitedAt = HashMap<Int, Long>()
    /** A cache reached but not (yet) judged paid is skipped until this time, then retried: an unpaid cache means "not yet", not "empty". */
    private val cacheRetryAt = HashMap<Int, Long>()
    private var pendingCache: PendingCache? = null
    /** Pickup cues counted so far and when the last one arrived: the server pays a cache from its open radius, a little before the bot reaches [CACHE_ARRIVE]. */
    private var cuesSeen = 0
    private var lastCueAt = Long.MIN_VALUE
    private class PendingCache(val index: Int, val paidBefore: Int, val verdictAt: Long)
    private val visitedAt = HashMap<Int, Long>()
    private val ignoredStars = HashMap<Int, Long>()
    private val starsSeen = HashMap<Int, DoubleArray>()
    private var starApproachId = -1
    /** Barrels / chests / crafting tables near the bot that pay a star when broken (ADR-0318 `star_blocks`). */
    private val starBlocks = StarBlockScanner(bot.terrain)
    private var starBlockTarget: Triple<Int, Int, Int>? = null
    /** The dig whose payout is still awaited: block, `star/pickup` cue count when it started, and when the verdict is due. */
    private var pendingDig: PendingDig? = null
    private var unpaidBreaks = 0
    /** True once [STAR_BLOCK_UNPAID_LIMIT] broken blocks in a row paid no star: the block bucket is empty, stop breaking for good. */
    @Volatile var starBlocksDry = false; private set
    private class PendingDig(val block: Triple<Int, Int, Int>, val paidBefore: Int, val verdictAt: Long)
    private var starApproachSince = 0L
    private var highY = Double.NEGATIVE_INFINITY
    private var fellRecently = false
    /** Tick of the running travel jump (-1 = on the ground). */
    private var jumpStep = -1
    private var jumpDash = false
    private val stuck = StuckEscapePolicy()
    private val ineffective = IneffectiveTargets()
    private val selector = TargetSelector()
    private val velocity = VelocityTracker()
    /** Extra-skill policy (own cooldowns) per hotbar slot of a [loadout] weapon, built from the item's content bindings. */
    private var skillPolicies: Map<Int, SkillUsePolicy> = emptyMap()
    /** Pending SNEAK_RIGHT cast: sneak went out at this time, the click follows on a later tick (0 = none). */
    private var skillSneakAt = 0L
    private var skillYaw = 0f
    private var skillPitch = 0f
    /** Health at the previous tick (-1 = unknown: just respawned / entered the fight), to detect damage taken. */
    private var lastHealth = -1f
    private var lastTargetId = -1
    private var chaseNoted = false
    /** Extra skill casts, for reports and tests. */
    val skillCasts = AtomicInteger()
    private var planner: ModePlanner? = null
    private var economy: EconomyPlanner? = null
    @Volatile private var decisionKindNow: String = "idle"
    /** Read-only for metrics: the mode role of the last plan step (null = no mode plan / no role yet). */
    val currentRole: Role? get() = planner?.role
    /** Read-only for metrics: `Dest.kind` of the current decision (`fight`, `mode`, `star`, `econ-shop`, `idle`, ...). */
    val currentDecisionKind: String get() = decisionKindNow
    /** Economy time spent in the current slice (only ticks that actually walk an economy step count, so a fight or a star does not eat it). */
    private var econActiveMs = 0L
    private var econLastTickAt = 0L
    /** Last tick the economy owned the destination (the economy outranks far enemies and stars while it did), and the last tick the bot lost health. */
    private var econOwnedAt = -1_000_000L
    private var lastHitAt = -1_000_000L
    private var econCooldownUntil = 0L
    /** Kind (`shop` / `collect` / `mine`) of the last economy step, for reports and tests. */
    @Volatile var economyKind: String = "-"; private set
    private var observed: ObservedModeFacts? = null
    private var lastStrikeAt = 0L
    private var lastToolCastAt = 0L
    /** Look direction toward the pillar block being struck this tick (null = keep the heading). */
    private var strikeLook: Pair<Float, Float>? = null
    /** Tool skill casts at pillars, for reports and tests. */
    val toolCasts = AtomicInteger()
    private var sidestepUntil = 0L
    private var sidestepSign = 1
    private var forceJump = false
    private var lastBreakAt = 0L
    /** Escapes taken, for reports and tests. */
    val stuckEscapes = AtomicInteger()
    val blocksBroken = AtomicInteger()

    fun start(): BotBrain {
        exec.scheduleAtFixedRate({ runCatching { tick() }.onFailure { bot.note("bot ${bot.name}: brain error $it") } }, 500, 50, TimeUnit.MILLISECONDS)
        return this
    }

    override fun close() {
        mode = Mode.IDLE
        exec.shutdownNow()
    }

    /** What the brain was doing every ~2.5 s of the fight (decision kind, status, position): for stall diagnosis in reports. */
    fun traceText(): String = trace.joinToString(" ; ")

    fun summary(): String = "swings=${swings.get()} drinks=${drinks.get()} shots=${shots.get()} reloads=${reloads.get()} enemyTicks=${ticksWithEnemy.get()} edgeBlocks=${blockedByEdge.get()} stars=${starsCollected.get()} falls=${edgeFalls.get()} sight=${firstSightMs}ms attack=${firstAttackMs}ms searches=${nav.searches.get()} stuckReplans=${nav.stuckReplans.get()} maxSearchUs=${nav.maxSearchMicros} avgNodes=${nav.expandedTotal.get() / nav.searches.get().coerceAtLeast(1)} goal=${objective?.name}/$goal loadout=$loadout"

    // --- the tick ------------------------------------------------------------------------------------------------

    fun tick() {
        val now = bot.now()
        if (!bot.joined) return
        dismissScreens()
        if (mode != Mode.FIGHT) {
            wasFighting = false
            endTravel()
            idleNudge(now)
            return
        }
        if (bot.health == 0f) {
            endTravel()
            resetCombatMemory()
            planner?.onDeath(bot.x, bot.z)
            if (now - lastRespawnAt > 1_000) { lastRespawnAt = now; bot.respawn() }
            return
        }
        if (bot.spectating) {
            // Death cam: the server owns us until it respawns us. Stay quiet.
            endTravel()
            resetCombatMemory()
            planner?.onDeath(bot.x, bot.z)
            idleNudge(now)
            return
        }
        if (!wasFighting) {
            wasFighting = true
            resetCombatMemory()
            spawnX = bot.x; spawnZ = bot.z; spawnY = bot.y
            // Stages are authored around (0,0) and pasted into a slot on a fixed grid (ADR-0255): find that slot's origin.
            val originX = Math.round(spawnX / SLOT_PITCH) * SLOT_PITCH
            val originZ = Math.round(spawnZ / SLOT_PITCH) * SLOT_PITCH
            nav.originX = originX; nav.originZ = originZ
            objective = objectives?.objectiveFor(spawnX - originX, spawnZ - originZ)
                ?.let { it.copy(x = it.x + originX, z = it.z + originZ) }
            starPoints = objectives?.starPoints().orEmpty().map { (x, z) -> (x + originX) to (z + originZ) }
            cachePoints = objectives?.cachePoints().orEmpty().map { ObjectiveSource.CachePoint(it.x + originX, it.y, it.z + originZ, it.respawnMs) }
            cacheVisitedAt.clear()
            cacheRetryAt.clear()
            pendingCache = null
            fightStartedAt = now
            nav.clear()
            retreating = false
            stuck.reset()
            selector.seedTies(TieBreak.seed(matchKey(originX, originZ), bot.name))
            planner = buildPlanner(originX, originZ, matchKey(originX, originZ))
            economy = buildEconomy(originX, originZ)
            econActiveMs = 0L; econLastTickAt = 0L; econOwnedAt = -1_000_000L; lastHitAt = -1_000_000L
            econCooldownUntil = if (planner != null) now + ECON_OPENING_MS else 0L
        }
        trackFall()
        if (now - lastTraceAt >= 2_500) {
            lastTraceAt = now
            if (trace.size < 60) trace += "${(now - fightStartedAt) / 1000}s %s (%.0f,%.0f)".format(lastKind, bot.x, bot.z)
        }
        resolveLoadoutIfNeeded(now)
        tickReload(now)
        tickSkillCast(now)

        // The target: visible enemies only (line of sight), remembered for a few seconds after it breaks (chase to the last spot).
        val foe = pickTarget(now)
        val chasing = foe?.takeIf { it.remembered }
        if (chasing != null) {
            if (!chaseNoted) { chaseNoted = true; planner?.noteLastKnown(chasing.lastX, chasing.lastZ, now) }
        } else {
            chaseNoted = false
        }
        // A step can limit how far an enemy may be for the bot to leave it (a TNT carrier runs, a guard stays at its post;
        // a pillar attacker also takes threats and easy kills farther away): ModePlanner.engages decides.
        val visible = foe?.takeIf { !it.remembered }
        val enemy = visible?.seen?.takeIf { e ->
            val pl = planner
            if (pl != null) pl.engages(Sighting(visible.name, e.x, e.z, visible.hpFraction), now, bot.x, bot.z) else true
        }?.takeIf { e -> !econIgnores(e.x, e.z, now) }
        val hasLos = visible?.hasLos ?: true
        trackStars(now)
        judgeStarBlockDig(now)
        judgeCache(now)
        updateRetreat(enemy, now)
        tryDrinkPotion(now)
        if (chooseNormaGoal) updateGoal(enemy, now)
        var yaw = bot.yawDeg
        var pitch = bot.pitchDeg
        var speed = skill.walkSpeed
        var heading: Double? = null // world-space direction of travel, radians in (x, z)
        var standStill = false

        val starDest = starTarget(now)
        val enemyFlat = if (enemy != null) hypot(enemy.x - bot.x, enemy.z - bot.z) else Double.MAX_VALUE
        // Stars first while the goal is STAR and the enemy is not close; a close enemy always wins the attention.
        val goForStar = starDest != null && enemyFlat > STAR_YIELD_RANGE && !retreating

        val fighting = SprintJumpPolicy.inCombat(
            enemyFlat = enemyFlat.takeIf { enemy != null }, retreating = retreating, reloading = now < reloadBusyUntil,
        )
        if (enemy != null) {
            if (fighting) endTravel() // combat movement is never sprint-jumped; a far enemy is a trip (dash-jump when long)
            stuck.reset()
            ineffective.engage(enemy.id, now, bot.damageNumbers.size, swings.get() + shots.get())
            ticksWithEnemy.incrementAndGet()
            if (firstSightMs < 0) firstSightMs = now - fightStartedAt
            lastEnemyAt = now; lastEnemyX = enemy.x; lastEnemyZ = enemy.z
            val dx = enemy.x - bot.x
            val dz = enemy.z - bot.z
            val flat = hypot(dx, dz)
            val dy = (enemy.y + 1.0) - (bot.y + 1.62)
            val dist = sqrt(flat * flat + dy * dy)
            yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            pitch = (-Math.toDegrees(atan2(dy, flat.coerceAtLeast(0.01)))).toFloat()
            val weapon = chooseWeapon(dist, now)
            heading = atan2(dz, dx)
            if (weapon?.kind == WeaponKind.RANGED) {
                // Lead + gravity aim at the chest from the eye, with the velocity estimated from the entity positions.
                val v = velocity.velocity(enemy.id)
                val sol = AimPolicy.solve(bot.x, bot.y + EYE_HEIGHT, bot.z, enemy.x, enemy.y + CHEST_HEIGHT, enemy.z, v[0], v[1], v[2], AimPolicy.profileFor(weapon))
                yaw = sol.yaw
                pitch = sol.pitch
            }
            when {
                weapon?.kind == WeaponKind.RANGED -> {
                    when (WeaponPolicy.move(weapon, flat, hasLos)) {
                        WeaponPolicy.Move.APPROACH, WeaponPolicy.Move.CLOSE_IN -> Unit // approach along heading
                        WeaponPolicy.Move.BACK_OFF -> heading += PI
                        WeaponPolicy.Move.STRAFE -> { heading += strafeSign * PI / 2; speed *= 0.65 }
                    }
                }
                else -> {
                    if (flat <= 1.7) standStill = true
                    else if (flat < 3.2) { heading += strafeSign * 0.5; speed *= 0.8 }
                    speed = min(speed, (flat - 1.5).coerceAtLeast(0.0)).coerceAtLeast(if (standStill) 0.0 else 0.05)
                }
            }
            if (now > strafeUntil) { strafeSign = -strafeSign; strafeUntil = now + 1_000 + rnd.nextInt(1_600) }
            act(enemy, weapon, dist, now, yaw, pitch, hasLos)
            trySkill(weapon, dist, hasLos, now, yaw, pitch)
            lastKind = "fight>local flat=%.0f".format(flat)

            // Long way, a different height, a gap in the way, a star to fetch or a retreat: plan a route (A*) instead of steering.
            // A ranged bot with a clear line inside its firing band steers locally (strafe / back off) instead of walking a route.
            decisionKindNow = "fight"
            val holdsBand = weapon?.kind == WeaponKind.RANGED && hasLos && flat <= WeaponPolicy.RANGED_MAX
            val needRoute = retreating || goForStar || (flat > LOCAL_STEER_RANGE && !holdsBand) || abs(enemy.y - bot.y) > 3.0 ||
                (flat > 3.0 && now - localBlockedAt < 2_000)
            if (needRoute) {
                val d = when {
                    retreating -> Dest(spawnX, spawnZ, null, 3.0, "retreat")
                    goForStar -> starDest!!
                    else -> Dest(enemy.x, enemy.z, enemy.y, if (weapon?.kind == WeaponKind.RANGED) WeaponPolicy.RANGED_MAX - 3.0 else 1.8, "enemy")
                }
                // keep the enemy in the crosshair while walking the route
                val routeSpeed = travel(d, inCombat = fighting)
                val st = nav.follow(bot, d.x, d.z, d.y, d.arrive, routeSpeed, now, yaw, pitch)
                if (st != Navigator.Status.MOVING) endTravel()
                lastKind = "fight>${d.kind}:$st"
                if (st == Navigator.Status.MOVING) { dwellOn(d, now); return }
                if (st == Navigator.Status.NO_PATH && d.kind == "star") ignoreStar(d.starId, now)
                bot.moveTo(bot.x, bot.y, bot.z, yaw, pitch)
                return
            }
        } else {
            ineffective.disengage()
            strikeLook = null
            val dest = chasing?.takeIf { !offLimits(now, it.lastX, it.lastZ) && planner?.inEnemyZone(it.lastX, it.lastZ) != true }?.let { Dest(it.lastX, it.lastZ, null, CHASE_ARRIVE, "enemy-chase") } ?: chooseDest(now, starDest)
            decisionKindNow = dest?.kind ?: "idle"
            if (dest == null) {
                endTravel()
                standStill = true
            } else {
                if (escapeStuck(dest, now)) return
                val travelSpeed = travel(dest)
                val st = nav.follow(bot, dest.x, dest.z, dest.y, dest.arrive, travelSpeed, now, null, 0f)
                if (st != Navigator.Status.MOVING) endTravel()
                lastKind = "go>${dest.kind}:$st"
                if (st == Navigator.Status.ARRIVED) stuck.reset()
                if (st != Navigator.Status.NO_PATH) noPathStreak = 0
                when (st) {
                    Navigator.Status.MOVING -> { dwellOn(dest, now); return }
                    Navigator.Status.ARRIVED -> {
                        arrivedAt(dest, now)
                    }
                    Navigator.Status.NO_PATH -> {
                        if (dest.kind.startsWith("econ-") && noPathGivesUp(dest, st)) lastEconStep?.let { economy?.unreachable(it, now) }
                        if (dest.kind == "mode") modePausedUntil = now + 5_000 // unreachable for now: patrol / roam meanwhile
                        if (dest.kind == "star") ignoreStar(dest.starId, now) else if (dest.kind != "enemy-seen") arrivedAt(dest, now, unreachable = true)
                    }
                }
                standStill = true
            }
        }

        if (standStill || heading == null) {
            val look = strikeLook
            if (look != null) bot.moveTo(bot.x, bot.y, bot.z, look.first, look.second) else bot.moveTo(bot.x, bot.y, bot.z, yaw, pitch)
        } else {
            move(heading, speed, yaw, pitch, now)
        }
    }

    /**
     * Server-opened windows (the Norma goal picker opens 2 s after match start and at each level-up) swallow clicks
     * until closed. The bot answers by closing them: closing keeps the default goal, STAR.
     */
    private fun dismissScreens() {
        val seen = bot.screens.size
        if (seen < screensSeen) screensSeen = 0 // the bot hopped servers and its screen list was reset
        if (seen == screensSeen) return
        val last = bot.screens.lastOrNull()
        screensSeen = seen
        sentGoal = null // the picker resets the goal; say it again
        if (last != null) bot.closeWindow(last.containerId)
    }

    private fun idleNudge(now: Long) {
        if (now - lastIdleNudgeAt < 1_000) return
        lastIdleNudgeAt = now
        bot.nudge()
    }

    // --- target selection ------------------------------------------------------------------------------------------

    /**
     * The target choice of this tick. [seen] is null for a [remembered] target (out of sight: walk to [lastX]/[lastZ], never
     * aim or shoot); a visible one always has line of sight ([TargetSelector] only picks those).
     */
    private class Foe(val seen: Seen?, val name: String?, val hasLos: Boolean, val hpFraction: Double?, val remembered: Boolean, val lastX: Double, val lastZ: Double)

    private fun pickTarget(now: Long): Foe? {
        val myTeam = bot.teamOf(bot.name)
        val eyeY = bot.y + EYE_HEIGHT
        val candidates = ArrayList<TargetPolicy.Candidate>()
        for (e in bot.entities.values) {
            if (e.type != "PLAYER" || e.id == bot.selfEntityId) continue
            velocity.observe(e.id, now, e.x, e.y, e.z)
            if (ineffective.ignored(e.id, now)) continue
            // No Tab-list entry (enemy team hidden from the list): no name, team or game mode - an unlisted player is
            // someone who is not on our team, and a dead one is caught by IneffectiveTargets (no hit lands).
            val name = bot.playerNames[e.uuid]
            candidates += TargetPolicy.Candidate(
                id = e.id, name = name ?: "?${e.uuid}", team = name?.let { bot.teamOf(it) },
                spectating = bot.isSpectator(e.uuid),
                x = e.x, y = e.y, z = e.z, listed = name != null,
                hasLos = LineOfSight.clear(bot.terrain, bot.x, eyeY, bot.z, e.x, e.y + CHEST_HEIGHT, e.z),
                hpFraction = bot.healthOf(e.id)?.toDouble(),
            )
        }
        noteDamageTaken(now, myTeam, candidates)
        val choice = selector.select(now, bot.name, myTeam, bot.x, bot.y, bot.z, candidates, SIGHT_RANGE)
        val target = choice.target ?: return null
        lastTargetId = target.id
        val name = target.name.takeIf { target.listed }
        if (choice.remembered) return Foe(null, name, false, target.hpFraction, true, choice.lastX, choice.lastZ)
        val seen = bot.entities[target.id] ?: return null
        return Foe(seen, name, target.hasLos, target.hpFraction, false, seen.x, seen.z)
    }

    /**
     * Damage taken = the bot's health dropped since the last tick. The client does not say who hit, so the attacker is a
     * heuristic: the current target if it is eligible, has line of sight and is within [WeaponPolicy.FIRE_MAX] (shots and
     * swings come from something that sees us; a look direction is not observable on [Seen]), else the nearest such enemy.
     * It gets the selector's threat bonus, and the mode planner learns the bot is under attack ([ModePlanner.noteDamaged]).
     */
    private fun noteDamageTaken(now: Long, myTeam: String?, candidates: List<TargetPolicy.Candidate>) {
        val hp = bot.health
        val dropped = lastHealth >= 0f && hp < lastHealth - 0.01f
        lastHealth = hp
        if (!dropped) return
        lastHitAt = now
        planner?.noteDamaged(now)
        val threats = candidates.filter {
            TargetPolicy.eligible(bot.name, myTeam, it) && it.hasLos && hypot(it.x - bot.x, it.z - bot.z) <= WeaponPolicy.FIRE_MAX
        }
        val attacker = threats.firstOrNull { it.id == lastTargetId } ?: threats.minByOrNull { hypot(it.x - bot.x, it.z - bot.z) }
        if (attacker != null) { selector.noteAttackedBy(attacker.id, now); planner?.noteAttackerTeam(now, attacker.team) }
    }

    /** New life or new match: forget targets, threats, a half-sent skill cast. */
    private fun resetCombatMemory() {
        selector.reset()
        lastTargetId = -1
        chaseNoted = false
        lastHealth = -1f
        if (skillSneakAt != 0L) { skillSneakAt = 0L; bot.sneak(false) }
    }

    /**
     * Where to walk with nobody in sight: a star, a generator that may hold stars (STAR goal), a recent enemy
     * sighting, then the mode objective and a patrol of the stage's points of interest, else roam.
     */
    /** Annihilation Preparation phase: a spot inside an enemy base, where the bot has nothing to do until the pillars open. */
    private fun offLimits(now: Long, x: Double, z: Double) = planner?.offLimits(now, x, z) == true

    private fun chooseDest(now: Long, starDest: Dest?): Dest? {
        // An attacker on enemy spawn ground leaves it before anything else (no fighting, digging or star fetching there).
        planner?.zoneEscape(now, bot.x, bot.z)?.let { st ->
            return Dest(st.x, st.z, st.y, st.arrive, "mode", zone = st.zone)
        }
        if (planner?.zoneTrapped == true && "trapped" !in trace) trace += "trapped"
        if (starDest != null) {
            // A bot on an economy trip finishes it (mine until a piece is affordable, then buy) unless the star is right here or it is being hit.
            if (!normaCheckPending && (starPickupCues() > 0 || now - fightStartedAt > ECON_STAR_FIRST_MS) && now - lastHitAt > ECON_HIT_MS && hypot(starDest.x - bot.x, starDest.z - bot.z) > ECON_STAR_DETOUR) economyDest(now)?.let { return it }
            return starDest
        }
        normaHomeDest()?.let { return it }
        planner?.let { pl ->
            val markers = bot.entities.values.filter { bot.entityTextsById[it.id]?.contains(ModePlanner.LOOSE_TAG) == true }.map { it.x to it.z }
            observed?.update(markers, sightings())
            observed?.updateRainmaker(bot.bossBarTitles, glowingPlayers(), bot.bossBarColors)
            pl.noteRoster(roster())
            observed?.updatePillarLabels(bot.entityTextsById.values.flatten())
            pl.noteMarkers(bot.entities.values.filter { bot.entityTextsById[it.id]?.contains(ModePlanner.LOOSE_TAG) == true }.map { it.x to it.z })
            pl.noteConfirmed(bot.sounds.count { it.contains("objective/pickup") })
            if (pl.clickWanted && now - lastClickAt >= CLICK_INTERVAL_MS) {
                bot.entities.values.filter { it.type.contains("INTERACTION", ignoreCase = true) && hypot(it.x - bot.x, it.z - bot.z) <= CLICK_REACH }
                    .minByOrNull { hypot(it.x - bot.x, it.z - bot.z) }?.let { bot.interact(it.id); lastClickAt = now }
            }
        }
        baseCacheDest(now)?.let { return it }
        economyDest(now)?.let { return it }
        // A cache close by is a short detour even while the mode plan runs (never while carrying the objective).
        if (goal == Goal.STAR && !offence(now) && !hungry(now) && planner?.let { it.holdingTnt || it.carried > 0 || it.clickWanted } != true) {
            nearestCache(now, CACHE_DETOUR_RANGE)?.let { return it }
        }
        // A paying block close by (barrel / chest / crafting table) is a short detour while nothing is in sight (never while carrying the objective).
        if (!starBlocksDry && goal == Goal.STAR && !offence(now) && planner?.let { it.holdingTnt || it.carried > 0 || it.clickWanted } != true) {
            starBlocks.tick(now, bot.x, bot.y, bot.z)
            starBlockTarget = starBlocks.nearest(bot.x, bot.y, bot.z, rank = if (planner?.preparing(now) == true) teamSlot().first else 0)
            starBlockTarget?.let { return Dest(it.first + 0.5, it.third + 0.5, it.second.toDouble(), STAR_BLOCK_ARRIVE, "block") }
        }
        if (now >= modePausedUntil && !hungry(now)) planner?.step(now, bot.x, bot.z)?.let { st ->
            return Dest(st.x, st.z, st.y, st.arrive, "mode", zone = st.zone, strike = st.strike, affinity = st.affinity)
        }
        if (goal == Goal.STAR && (starPoints.isNotEmpty() || cachePoints.isNotEmpty())) {
            // Nearest valuable source: caches pay a bundle (weighted), candidate spots only hold a star while the rotation is on them
            // (a star that is visible is already handled by starTarget); no spot is camped, each is skipped for GEN_COOLDOWN_MS after a look.
            var bestI = -1
            var bestScore = Double.MAX_VALUE
            val hungryNow = hungry(now)
            for ((i, p) in starPoints.withIndex()) {
                if (now - (visitedAt[i] ?: -GEN_COOLDOWN_MS) < GEN_COOLDOWN_MS) continue
                if (offLimits(now, p.first, p.second)) continue
                if (hungryNow && hypot(p.first - spawnX, p.second - spawnZ) > BASE_SWEEP_RADIUS) continue
                val score = hypot(p.first - bot.x, p.second - bot.z)
                if (score < bestScore) { bestScore = score; bestI = i }
            }
            val cache = if (hungryNow) null else nearestCache(now, Double.MAX_VALUE)?.takeIf { !offLimits(now, it.x, it.z) }
            if (bestI < 0 && hungryNow) {
                // Every base spot was just looked at: wait at one (rotated by slot) for the next pop instead of walking off.
                val near = starPoints.indices.filter { hypot(starPoints[it].first - spawnX, starPoints[it].second - spawnZ) <= BASE_SWEEP_RADIUS }
                near.getOrNull(teamSlot().first % near.size.coerceAtLeast(1))?.let { return Dest(starPoints[it].first, starPoints[it].second, null, 2.0, "gen", it) }
            }
            if (cache != null && hypot(cache.x - bot.x, cache.z - bot.z) / CACHE_VALUE_WEIGHT < bestScore) return cache
            if (bestI >= 0) return Dest(starPoints[bestI].first, starPoints[bestI].second, null, 2.0, "gen", bestI)
        }
        if (now - lastEnemyAt < 8_000 && !offLimits(now, lastEnemyX, lastEnemyZ) && planner?.inEnemyZone(lastEnemyX, lastEnemyZ) != true) return Dest(lastEnemyX, lastEnemyZ, null, 3.0, "enemy-seen")
        val patrol = patrolPoints().filter { !offLimits(now, it.first, it.second) }
        if (patrol.isNotEmpty()) {
            val i = patrolIndex % patrol.size
            val p = patrol[i]
            val isObjective = i == 0 && objective != null
            return Dest(p.first, p.second, null, if (isObjective) 3.5 else 3.0, if (isObjective) "objective" else "patrol")
        }
        if (now > wanderUntil) {
            wanderHeading = rnd.nextDouble() * 2 * PI
            wanderUntil = now + 2_500 + rnd.nextInt(3_000)
        }
        return Dest(bot.x + cos(wanderHeading) * 20, bot.z + sin(wanderHeading) * 20, null, 1.0, "roam")
    }

    /**
     * The Norma check (the level only rises at the own base): once the server's "Norma goal met" action bar shows up,
     * walk back to the spawn (inside the base zone) and send the check command. A refused check (stars lost on the
     * way) re-arms through the next notice.
     */
    private fun normaHomeDest(): Dest? {
        val bars = bot.actionBars
        while (normaBarsSeen < bars.size) {
            if (NORMA_READY.containsMatchIn(bars[normaBarsSeen].text)) normaCheckPending = true
            normaBarsSeen++
        }
        if (!normaCheckPending) return null
        if (hypot(spawnX - bot.x, spawnZ - bot.z) <= NORMA_HOME_ARRIVE) {
            bot.command("astera norma check")
            normaCheckPending = false
            return null
        }
        return Dest(spawnX, spawnZ, null, NORMA_HOME_ARRIVE - 1.0, "norma-home")
    }

    private var normaBarsSeen = 0
    private var normaCheckPending = false

    /** Enemy objective first, then the generator pedestals, then home: a loop that crosses the whole stage. */
    private fun patrolPoints(): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        objective?.let { out += (it.x + 0.5) to (it.z + 0.5) }
        out += starPoints
        return out
    }

    /** Nearest exploration cache within [range] that is not on its respawn cooldown (visited caches are remembered for their respawn time). */
    private fun nearestCache(now: Long, range: Double): Dest? {
        var bestI = -1
        var bestD = Double.MAX_VALUE
        for ((i, c) in cachePoints.withIndex()) {
            val seen = cacheVisitedAt[i]
            if (seen != null && now - seen < c.respawnMs) continue
            if (now < (cacheRetryAt[i] ?: 0L)) continue
            val d = hypot(c.x - bot.x, c.z - bot.z) + abs(c.y - bot.y) * 0.5
            if (d <= range && d < bestD && !offLimits(now, c.x, c.z)) { bestD = d; bestI = i }
        }
        if (bestI < 0) return null
        val c = cachePoints[bestI]
        return Dest(c.x, c.z, c.y, CACHE_ARRIVE, "cache", bestI)
    }

    private fun arrivedAt(d: Dest, now: Long, unreachable: Boolean = false) {
        if (d.strike != null && hypot(d.x - bot.x, d.z - bot.z) <= STRIKE_REACH) strikeBlock(d.strike, d.affinity, now)
        when (d.kind) {
            "cache" -> if (pendingCache == null && now >= (cacheRetryAt[d.starId] ?: 0L)) {
                // Judged by the pickup cue within CACHE_PAY_WINDOW_MS; until then (and after an unpaid visit) the cache is off the list for CACHE_RETRY_MS.
                if (now - lastCueAt <= CACHE_PAY_LOOKBACK_MS) cacheVisitedAt[d.starId] = now
                else {
                    pendingCache = PendingCache(d.starId, starPickupCues(), now + CACHE_PAY_WINDOW_MS)
                    cacheRetryAt[d.starId] = now + CACHE_RETRY_MS
                }
            }
            "block" -> breakStarBlock(unreachable, now)
            "gen" -> {
                if (unreachable) { visitedAt[d.starId] = now; return }
                if (dwellUntil == 0L) dwellUntil = now + GEN_DWELL_MS
                else if (now >= dwellUntil) { visitedAt[d.starId] = now; dwellUntil = 0L }
            }
            "star" -> {
                if (starArrivedSince == 0L) starArrivedSince = now
                else if (now - starArrivedSince > 1_500) ignoreStar(d.starId, now)
            }
            "patrol", "objective" -> {
                if (dwellUntil == 0L) dwellUntil = now + (if (unreachable) 0 else 800)
                if (now >= dwellUntil) { patrolIndex++; dwellUntil = 0L }
            }
            "roam" -> wanderUntil = 0L
        }
    }

    /**
     * Breaks the targeted star block: face it, take the axe if the kit has one, then hold the dig for the vanilla break time of
     * the tool in hand ([DigTime]); one dig at a time until its payout is judged. Out of reach or unreachable = give up on it.
     */
    private fun breakStarBlock(unreachable: Boolean, now: Long) {
        val b = starBlockTarget ?: return
        val dx = b.first + 0.5 - bot.x
        val dz = b.third + 0.5 - bot.z
        val dy = b.second + 0.5 - (bot.y + EYE_HEIGHT)
        if (unreachable || sqrt(dx * dx + dy * dy + dz * dz) > STRIKE_REACH) { starBlocks.giveUp(b); starBlockTarget = null; return }
        strikeLook = Math.toDegrees(atan2(-dx, dz)).toFloat() to (-Math.toDegrees(atan2(dy, hypot(dx, dz).coerceAtLeast(0.01)))).toFloat()
        if (pendingDig != null) return
        val axe = toolSlots[LoadoutClassifier.Affinity.AXE]
        if (axe != null && bot.selectedSlot != axe) {
            if (now - lastSwitchAt > 300) { lastSwitchAt = now; bot.selectHotbar(axe) }
            return
        }
        val holdMs = DigTime.holdMs(bot.hotbarName(bot.selectedSlot))
        pendingDig = PendingDig(b, starPickupCues(), now + holdMs + STAR_BLOCK_PAY_WINDOW_MS)
        starBlocks.noteDig(b)
        blocksBroken.incrementAndGet()
        bot.digAsync(b.first, b.second, b.third, holdMs)
    }

    /** The server plays the `astera:star/pickup` cue to the player whenever it pays a star (a capped payout plays nothing). */
    private fun starPickupCues(): Int = bot.sounds.count { it.contains("star/pickup") }

    /**
     * Judges the last star-block dig once its hold and [STAR_BLOCK_PAY_WINDOW_MS] have passed: the block is gone but no pickup cue
     * came = an unpaid break. [STAR_BLOCK_UNPAID_LIMIT] in a row = the block star bucket is empty; stop for the rest of the match.
     * (A star picked up elsewhere inside the window counts as paid, which only delays the verdict.)
     */
    private fun judgeStarBlockDig(now: Long) {
        val p = pendingDig ?: return
        if (now < p.verdictAt) return
        pendingDig = null
        val gone = bot.terrain.blockName(p.block.first, p.block.second, p.block.third).let { it != null && it !in StarBlockScanner.PAYING }
        if (!gone) return
        if (starPickupCues() > p.paidBefore) { unpaidBreaks = 0; return }
        if (++unpaidBreaks >= STAR_BLOCK_UNPAID_LIMIT) { starBlocksDry = true; starBlockTarget = null }
    }

    /** A cache visit counts (45 s cooldown) only once the server paid a star for it; an unpaid one is retried after [CACHE_RETRY_MS]. */
    private fun judgeCache(now: Long) {
        val cues = starPickupCues()
        if (cues > cuesSeen) { cuesSeen = cues; lastCueAt = now }
        val p = pendingCache ?: return
        if (starPickupCues() > p.paidBefore) {
            cacheVisitedAt[p.index] = now
            cacheRetryAt.remove(p.index)
            pendingCache = null
        } else if (now >= p.verdictAt) pendingCache = null
    }

    private var starArrivedSince = 0L
    private var modePausedUntil = 0L
    private var lastClickAt = 0L

    /** Digs [block] (an Annihilation pillar): the server turns every break attempt on a pillar block into damage to it. */
    private fun strikeBlock(block: Triple<Int, Int, Int>, affinity: LoadoutClassifier.Affinity?, now: Long) {
        // Face the block: the tool skill is aimed with the look direction.
        val dx = block.first + 0.5 - bot.x
        val dz = block.third + 0.5 - bot.z
        // Aim at the pillar entity's position (block y), the centre of its hit box (y-0.5..y+0.5); the block centre (y+0.5) is the
        // box's top edge and the ray there lands on the block face above it, which deals no pillar damage.
        val dy = block.second.toDouble() - (bot.y + 1.62)
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, hypot(dx, dz).coerceAtLeast(0.01)))).toFloat()
        strikeLook = yaw to pitch
        // The tool of the pillar's class (any tool when the kit lacks it); without one the bare hand digs.
        val toolSlot = affinity?.let { toolSlots[it] } ?: toolSlots.values.minOrNull()
        if (toolSlot != null && bot.selectedSlot != toolSlot) {
            if (now - lastSwitchAt > 300) { lastSwitchAt = now; bot.selectHotbar(toolSlot) }
            return
        }
        if (toolSlot != null && now - lastToolCastAt >= TOOL_CAST_INTERVAL_MS) {
            lastToolCastAt = now
            bot.useItem(yaw, pitch)
            toolCasts.incrementAndGet()
        }
        if (now - lastStrikeAt < STRIKE_INTERVAL_MS) return
        lastStrikeAt = now
        blocksBroken.incrementAndGet()
        bot.digAsync(block.first, block.second, block.third, 400)
    }

    /** The bot's team as it sees it: every Tab-listed team mate with its entity position (NaN out of sight, then the shared spawn) and whether it is alive. */
    private fun roster(): List<Mate> {
        val myTeam = bot.teamOf(bot.name) ?: return emptyList()
        val byUuid = bot.entities.values.filter { it.type == "PLAYER" }.associateBy { it.uuid }
        return bot.playerNames.mapNotNull { (uuid, name) ->
            if (name == bot.name || bot.teamOf(name) != myTeam) return@mapNotNull null
            val e = byUuid[uuid]
            Mate(name, e?.x ?: spawnX, e?.z ?: spawnZ, alive = !bot.isSpectator(uuid))
        }
    }

    /** Every player entity with the glowing flag (0x40): the carry-tnt carrier glows. Self counts, at its own position. */
    private fun glowingPlayers(): List<GlowingPlayer> =
        bot.entities.values.filter { it.type == "PLAYER" && ((bot.entityFlags[it.id] ?: 0) and 0x40) != 0 }.map { e ->
            val self = e.id == bot.selfEntityId
            GlowingPlayer(if (self) bot.name else bot.playerNames[e.uuid], if (self) bot.x else e.x, if (self) bot.z else e.z)
        }

    private fun sightings(): List<Sighting> {
        val myTeam = bot.teamOf(bot.name)
        return bot.entities.values.filter { it.type == "PLAYER" && it.id != bot.selfEntityId }.mapNotNull { e ->
            val name = bot.playerNames[e.uuid]
            val team = name?.let { bot.teamOf(it) }
            if (bot.isSpectator(e.uuid) || (team != null && team == myTeam)) null else Sighting(name, e.x, e.z)
        }
    }

    /** The lull-time shop economy for the bot's team (null when the stage has no shop or no upgrade ladder). */
    private fun buildEconomy(originX: Double, originZ: Double): EconomyPlanner? {
        val src = objectives ?: return null
        val team = src.ownTeamAt(spawnX - originX, spawnZ - originZ) ?: return null
        val ladder = src.economyLadder().takeIf { it.isNotEmpty() } ?: return null
        val sites = src.economySites(team)?.shifted(originX, originZ) ?: return null
        return EconomyPlanner(sites, ladder, spread = bot.name.hashCode())
    }

    /** Ore the bot carries by item id (the bag's display names, see [EconomyPlanner.oreOfName]). */
    private fun carriedOre(): Map<String, Int> =
        EconomyPlanner.oreCounts(bot.carriedStacks())

    /**
     * Lull-time economy: collect base-generator ore, mine Mid ore nodes, walk to the own base shop once the next upgrade is
     * affordable. The mode objective and fights keep priority: with a mode plan the economy runs only in slices of
     * [ECON_SLICE_MS] after [ECON_OPENING_MS] of match start, never while carrying the objective or with an enemy seen in the last
     * [LULL_MS], and hands over to the plan for [OBJECTIVE_SLICE_MS] after each slice. (Enemies in sight never reach here: the fight branch wins.)
     */
    private var lastEconStep: EconomyPlanner.Step? = null
    private var noPathStreak = 0
    private var noPathKey = ""

    /** Consecutive NO_PATH ticks towards the same goal; [NO_PATH_GIVE_UP_TICKS] of them mean the goal is not reachable, not a chunk still loading. */
    private fun noPathGivesUp(dest: Dest, st: Navigator.Status): Boolean {
        if (st != Navigator.Status.NO_PATH) { noPathStreak = 0; return false }
        val key = "${dest.kind}:${dest.x.toInt()}:${dest.z.toInt()}"
        if (key != noPathKey) { noPathKey = key; noPathStreak = 0 }
        return ++noPathStreak >= NO_PATH_GIVE_UP_TICKS
    }

    /** The economy trip outranks an enemy that is farther than [ECON_ENGAGE_RANGE] unless the bot was hit in the last [ECON_HIT_MS]. */
    private fun econIgnores(ex: Double, ez: Double, now: Long): Boolean =
        (economy?.rung ?: 1) == 0 && now - econOwnedAt <= 400 && now - lastHitAt > ECON_HIT_MS && hypot(ex - bot.x, ez - bot.z) > ECON_ENGAGE_RANGE

    private fun economyDest(now: Long): Dest? {
        val eco = economy ?: return null
        if (hungry(now)) return null
        val pl = planner
        if (pl != null) {
            if (pl.holdingTnt || pl.carried > 0 || pl.clickWanted) { econActiveMs = 0L; econLastTickAt = 0L; return null }
            if (now < econCooldownUntil || now - lastEnemyAt < LULL_MS) { econLastTickAt = 0L; return null }
        }
        val st = eco.step(now, bot.x, bot.z, carriedOre(), toolSlots.containsKey(LoadoutClassifier.Affinity.PICKAXE), bot.carriedItemIds())
        if (st == null) { econActiveMs = 0L; econLastTickAt = 0L; return null }
        // A pillar attacker on the offence only buys what it already carries, and only when the shop is close; mining and shop trips are the other roles' work.
        if (pl != null && offence(now) && (st.kind != "shop" || hypot(st.x - bot.x, st.z - bot.z) > ECON_ATTACKER_SHOP_RANGE)) return null
        if (pl != null) {
            // The slice is economy time, not wall time: a fight or an interruption pauses it and the trip resumes where it stopped. A buy
            // in reach (the shop step) always finishes.
            val gap = now - econLastTickAt
            econLastTickAt = now
            if (st.kind != "shop") {
                if (gap in 1..300) econActiveMs += gap
                if (econActiveMs > ECON_SLICE_MS) {
                    econActiveMs = 0L; econLastTickAt = 0L; econCooldownUntil = now + (if (pl.role == Role.PILLAR_ATTACKER) ATTACKER_OBJECTIVE_SLICE_MS else OBJECTIVE_SLICE_MS)
                    return null
                }
            }
        }
        econOwnedAt = now
        economyKind = st.kind
        lastEconStep = st
        return Dest(st.x, st.z, null, st.arrive, "econ-${st.kind}", strike = st.strike, affinity = st.strike?.let { LoadoutClassifier.Affinity.PICKAXE })
    }

    /** Key of the running match for [TieBreak]: mode and stage slot (no match id reaches a bot; no clock, so a pick never changes between ticks). */
    private fun matchKey(originX: Double, originZ: Double) = "$modeId@${originX.toLong()},${originZ.toLong()}"

    private fun buildPlanner(originX: Double, originZ: Double, matchKey: String): ModePlanner? {
        val id = modeId ?: return null
        val src = objectives ?: return null
        if (id !in ModePlanner.MODES) return null
        val base = src.zones() ?: return null
        val own = src.ownTeamAt(spawnX - originX, spawnZ - originZ) ?: return null
        val world = object : ModePlanner.Zones {
            override fun point(name: String) = base.point(name)?.let { Triple(it.first + originX, it.second, it.third + originZ) }
            override fun names(prefix: String) = base.names(prefix)
            override fun pillars() = base.pillars().map { it.copy(x = it.x + originX.toInt(), z = it.z + originZ.toInt()) }
            override fun spawns() = base.spawns().map { Triple(it.first, it.second + originX, it.third + originZ) }
        }
        val facts = ObservedModeFacts(
            id,
            solidAt = { x, y, z -> bot.terrain.isSolid(x, y, z) },
            pillarBlock = { team, tier -> world.pillars().firstOrNull { it.team == team && it.tier == tier }?.let { Triple(it.x, it.y, it.z) } },
            tntSpawn = world.point(ModePlanner.TNT_SPAWN)?.let { it.first to it.third },
        ).also { observed = it }
        val modeFacts = src.modeFacts(facts, originX, originZ) ?: facts
        return ModePlanner(id, bot.name, own, src.teams().filter { it != own }, world, facts = modeFacts, matchKey = matchKey, teamOf = { bot.teamOf(it) }).takeIf { it.handles() }
    }

    /**
     * Stuck escape for non-combat travel (see [StuckEscapePolicy]): hop, sidestep, re-plan around the blocked cell,
     * dig through. Returns true when this tick was spent on a sidestep.
     */
    private fun escapeStuck(dest: Dest, now: Long): Boolean {
        unbury(now)
        val moving = hypot(dest.x - bot.x, dest.z - bot.z) > dest.arrive
        when (stuck.update(now, bot.x, bot.z, moving)) {
            StuckEscapePolicy.Action.NONE -> Unit
            StuckEscapePolicy.Action.JUMP -> { stuckEscapes.incrementAndGet(); forceJump = true }
            StuckEscapePolicy.Action.SIDESTEP_LEFT -> { stuckEscapes.incrementAndGet(); sidestepSign = 1; sidestepUntil = now + 700 }
            StuckEscapePolicy.Action.SIDESTEP_RIGHT -> { stuckEscapes.incrementAndGet(); sidestepSign = -1; sidestepUntil = now + 700 }
            StuckEscapePolicy.Action.REPATH -> { stuckEscapes.incrementAndGet(); nav.markStuck(now) }
            StuckEscapePolicy.Action.BREAK_BLOCK -> { stuckEscapes.incrementAndGet(); breakAhead(dest, now) }
        }
        if (now < sidestepUntil) {
            val h = atan2(dest.z - bot.z, dest.x - bot.x) + sidestepSign * PI / 2
            endTravel()
            move(h, skill.walkSpeed * 0.8, bot.yawDeg, bot.pitchDeg, now)
            lastKind = "go>${dest.kind}:sidestep"
            return true
        }
        return false
    }

    /**
     * A block placed over the bot (arena restore, a rebuilt pillar ring) leaves it inside solid terrain, where no start cell exists
     * and every plan is NO_PATH for good. Dig the block at head height, then at the feet, until the bot stands in air again.
     */
    private fun unbury(now: Long) {
        if (now - lastBreakAt < 1_500) return
        val bx = Math.floor(bot.x).toInt()
        val bz = Math.floor(bot.z).toInt()
        val by = Math.floor(bot.y + 0.05).toInt()
        val y = listOf(by + 1, by).firstOrNull { bot.terrain.isSolid(bx, it, bz) == true } ?: return
        lastBreakAt = now
        blocksBroken.incrementAndGet()
        bot.digAsync(bx, y, bz, 400)
        nav.clear()
    }

    /** Digs the first solid block (head height, then feet height) in front towards [dest]; the server decides whether it breaks. */
    private fun breakAhead(dest: Dest, now: Long) {
        if (now - lastBreakAt < 1_500) return
        val dx = dest.x - bot.x
        val dz = dest.z - bot.z
        val d = hypot(dx, dz).coerceAtLeast(1e-6)
        val fx = Math.floor(bot.x + dx / d * 0.9).toInt()
        val fz = Math.floor(bot.z + dz / d * 0.9).toInt()
        val by = Math.floor(bot.y + 0.05).toInt()
        val y = listOf(by + 1, by).firstOrNull { bot.terrain.isSolid(fx, it, fz) == true } ?: return
        lastBreakAt = now
        blocksBroken.incrementAndGet()
        bot.digAsync(fx, y, fz, 400)
        nav.clear()
    }

    /** Called every tick the bot is on its way somewhere: gives up on a star it has chased for too long. */
    private fun dwellOn(d: Dest, now: Long) {
        starArrivedSince = 0L
        if (d.kind != "star") return
        if (starApproachId != d.starId) { starApproachId = d.starId; starApproachSince = now }
        else if (now - starApproachSince > STAR_CHASE_MS) ignoreStar(d.starId, now)
    }

    private fun ignoreStar(id: Int, now: Long) {
        ignoredStars[id] = now + 30_000
        starArrivedSince = 0L
        nav.clear()
    }

    /** A pillar attacker whose pillars can be hit: it is the team's offence and only picks up what lies on its way. In Preparation it gathers like everybody. */
    private fun offence(now: Long) = planner?.role == Role.PILLAR_ATTACKER && planner?.preparing(now) != true

    private fun isStarItem(e: Seen): Boolean {
        if (e.type != "ITEM") return false
        val texts = bot.entityTextsById[e.id]
        return texts == null || texts.isEmpty() || texts.any { it.contains('★') || it.contains("star", ignoreCase = true) }
    }

    /** The star pick-up to fetch: nearest dropped star item within reach (any distance with the STAR goal, a short detour with KILL). */
    private fun starTarget(now: Long): Dest? {
        // A pillar attacker is the team's offence: it only picks up a star on its way (ATTACKER_STAR_RANGE) instead of chasing
        // every star on the stage while the enemy pillars stand untouched.
        val limit = if (goal != Goal.STAR) KILL_DETOUR_RANGE else if (offence(now)) ATTACKER_STAR_RANGE else if (hungry(now)) BASE_SWEEP_RADIUS + 15.0 else STAR_SIGHT_RANGE
        var best: Seen? = null
        var bestD = Double.MAX_VALUE
        val sweep = if (goal == Goal.STAR) baseSweepStars(now) else emptyList()
        // Preparation: the stars lying at the own base are split over the team (slot k takes the k-th), so mates do not chase the same one.
        val share = if (sweep.size > 1) sweep.getOrNull(teamSlot().first % sweep.size) else null
        if (share != null) return Dest(share.x, share.z, null, 0.9, "star", share.id)
        for (e in bot.entities.values) {
            if (!isStarItem(e)) continue
            val until = ignoredStars[e.id]
            if (until != null && until > now) continue
            if (offLimits(now, e.x, e.z)) continue
            if (hungry(now) && hypot(e.x - spawnX, e.z - spawnZ) > BASE_SWEEP_RADIUS + 15.0) continue
            val d = hypot(e.x - bot.x, e.z - bot.z) + abs(e.y - bot.y) * 0.5
            if (d <= limit && d < bestD) { best = e; bestD = d }
        }
        return best?.let { Dest(it.x, it.z, null, 0.9, "star", it.id) }
    }

    /**
     * Preparation (ModePlanner.preparing, up to [HUNGRY_MAX_MS]) and this bot has not been paid a single star yet: it sweeps its own
     * base supply (stars, caches, paying blocks, the base star spots) before any role job, shop trip or far star chase.
     */
    private fun hungry(now: Long) =
        goal == Goal.STAR && now - fightStartedAt < HUNGRY_MAX_MS && planner?.preparing(now) == true && starPickupCues() == 0

    /** Preparation only: dropped stars within [BASE_SWEEP_RADIUS] of the own spawn, in a fixed (x, z, id) order shared by the whole team. */
    private fun baseSweepStars(now: Long): List<Seen> {
        if (planner?.preparing(now) != true) return emptyList()
        return bot.entities.values.filter { e ->
            isStarItem(e) && (ignoredStars[e.id] ?: 0L) <= now && hypot(e.x - spawnX, e.z - spawnZ) <= BASE_SWEEP_RADIUS && !offLimits(now, e.x, e.z)
        }.sortedWith(compareBy({ it.x }, { it.z }, { it.id }))
    }

    /** (this bot's index, team size) among the team's names (sorted, dead mates included), so the same split is computed by every mate without talking. */
    private fun teamSlot(): Pair<Int, Int> {
        val names = (roster().map { it.name } + bot.name).distinct().sorted()
        return names.indexOf(bot.name).coerceAtLeast(0) to names.size
    }

    /** Preparation: the own-base cache this bot opens first (caches within [BASE_SWEEP_RADIUS] of the spawn, one per team slot); null once opened or when none. */
    private fun baseCacheDest(now: Long): Dest? {
        if (goal != Goal.STAR || planner?.preparing(now) != true) return null
        // A bot without a star yet stays at the base for the opening pop (caches do not pay in the opening and the pop is first come): the cache trip is for bots that already have one.
        if (hungry(now)) return null
        // Only caches on the spawn's level (a roof or gallery cache needs a climb); one opener per cache, the other slots go elsewhere.
        val own = cachePoints.indices.filter { hypot(cachePoints[it].x - spawnX, cachePoints[it].z - spawnZ) <= BASE_SWEEP_RADIUS && cachePoints[it].y - spawnY <= 3.0 }
            .sortedWith(compareBy({ cachePoints[it].x }, { cachePoints[it].z }))
        val i = own.getOrNull(teamSlot().first) ?: return null
        val seen = cacheVisitedAt[i]
        if (seen != null && now - seen < cachePoints[i].respawnMs) return null
        if (now < (cacheRetryAt[i] ?: 0L)) return null
        val c = cachePoints[i]
        if (offLimits(now, c.x, c.z)) return null
        return Dest(c.x, c.z, c.y, CACHE_ARRIVE, "cache", i)
    }

    /** Counts stars picked up: a star item that vanishes while the bot stands next to it. */
    private fun trackStars(now: Long) {
        for (e in bot.entities.values) if (isStarItem(e)) starsSeen[e.id] = doubleArrayOf(e.x, e.y, e.z)
        val iter = starsSeen.entries.iterator()
        while (iter.hasNext()) {
            val (id, p) = iter.next()
            if (bot.entities.containsKey(id)) continue
            if (hypot(p[0] - bot.x, p[2] - bot.z) <= 3.5 && abs(p[1] - bot.y) <= 3.0) starsCollected.incrementAndGet()
            iter.remove()
        }
        ignoredStars.entries.removeIf { it.value < now }
    }

    private fun trackFall() {
        if (bot.y > highY) highY = bot.y
        if (!fellRecently && bot.y < highY - FALL_DEPTH) { fellRecently = true; edgeFalls.incrementAndGet() }
        else if (fellRecently && bot.y > highY - 4) fellRecently = false
    }

    /** Retreating at RETREAT_HP or lower with a consumable heal in the hotbar: select it and right-click (the skill has a 5 s cooldown). */
    private fun tryDrinkPotion(now: Long) {
        if (lastDrinkAt > 0 && now - lastDrinkAt < 3_000) peakHpAfterDrink = maxOf(peakHpAfterDrink.takeUnless { it.isNaN() } ?: 0f, bot.health)
        val hp = bot.health
        if (healSlot < 0 || !retreating || hp <= 0f || hp > RETREAT_HP || now - lastDrinkAt < 5_500) return
        if (bot.hotbar()[healSlot] == -1) { healSlot = -1; return }
        bot.selectHotbar(healSlot)
        lastSwitchAt = now
        bot.useItem(bot.yawDeg, bot.pitchDeg)
        lastDrinkAt = now
        lastDrinkHp = hp
        peakHpAfterDrink = hp
        drinks.incrementAndGet()
    }

    private fun updateRetreat(enemy: Seen?, now: Long) {
        val hp = bot.health
        val enemyNear = enemy != null && hypot(enemy.x - bot.x, enemy.z - bot.z) < 14.0
        if (!retreating) {
            if (hp > 0f && hp <= RETREAT_HP && enemyNear) { retreating = true; retreatSince = now; nav.clear() }
        } else if (hp >= RECOVER_HP || now - retreatSince > 10_000 || (!enemyNear && now - lastEnemyAt > 3_000)) {
            retreating = false; nav.clear()
        }
    }

    /** STAR goal while the field is quiet, KILL while an enemy is on us; re-sent after the picker window resets it. */
    private fun updateGoal(enemy: Seen?, now: Long) {
        if (starPoints.isEmpty()) return
        if (enemy != null && hypot(enemy.x - bot.x, enemy.z - bot.z) < 25.0) engagedAt = now
        val desired = if (now - engagedAt < 3_000) Goal.KILL else Goal.STAR
        if (desired != goal && now - lastGoalChangeAt > 8_000) { goal = desired; lastGoalChangeAt = now }
        if (sentGoal != goal && now - lastGoalSentAt > 3_000 && now - fightStartedAt > 3_000) {
            lastGoalSentAt = now
            sentGoal = goal
            bot.command("astera norma choose ${goal.name.lowercase()}")
        }
    }

    private fun noteAttack(now: Long) {
        if (firstAttackMs < 0) firstAttackMs = now - fightStartedAt
    }

    // --- movement ------------------------------------------------------------------------------------------------

    /**
     * Non-combat travel (objective, stars, waypoints, roaming) sprint-jumps per [SprintJumpPolicy]: sets the sprint state
     * and the jump-arc [BotHost.lift] that the next position packet (sent by [Navigator.follow]) carries. The floor pose
     * the navigator works on is not changed, so path and edge checks stay on the ground.
     */
    private fun travel(dest: Dest, inCombat: Boolean = false): Double {
        val dx = dest.x - bot.x
        val dz = dest.z - bot.z
        val dist = hypot(dx, dz)
        val moving = dist > dest.arrive
        val dirX = if (dist > 1e-6) dx / dist else 0.0
        val dirZ = if (dist > 1e-6) dz / dist else 0.0
        val aheadX = bot.x + dirX * JUMP_LOOKAHEAD
        val aheadZ = bot.z + dirZ * JUMP_LOOKAHEAD
        val groundAhead = bot.terrain.knows(aheadX, aheadZ) && bot.terrain.floorAt(aheadX, aheadZ, bot.y, 0, 1) != null
        val by = Math.floor(bot.y + 0.05).toInt()
        val headroom = listOf(bot.x to bot.z, aheadX to aheadZ).all { (cx, cz) ->
            (2..3).all { dy -> bot.terrain.isSolid(Math.floor(cx).toInt(), by + dy, Math.floor(cz).toInt()) == false }
        }
        val airborne = jumpStep >= 0
        val stepUp = nav.nextRise(bot) > 0.5
        val decision = SprintJumpPolicy.decide(
            inCombat = inCombat, moving = moving, airborne = airborne, groundAhead = groundAhead, headroom = headroom,
            stepUp = stepUp, forceJump = forceJump, distance = dist, dashing = jumpDash, walkSpeed = skill.walkSpeed,
        )
        if (decision.jump) forceJump = false
        if (!decision.sprint && !decision.jump && !airborne) { endTravel(); return decision.speed }
        bot.setSprinting(decision.sprint)
        if (airborne || decision.jump) {
            val step = if (airborne) jumpStep else 0
            if (!airborne) jumpDash = decision.sprint
            val lift = SprintJumpPolicy.lift(step)
            bot.lift = lift
            jumpStep = if (lift == 0.0) -1 else step + 1
            if (jumpStep < 0) jumpDash = false
        } else {
            bot.lift = 0.0
        }
        return decision.speed
    }

    /** Back on the ground, not sprinting. Idempotent; called whenever the bot stops travelling (combat, arrival, death, lobby). */
    private fun endTravel() {
        jumpStep = -1
        jumpDash = false
        bot.lift = 0.0
        bot.setSprinting(false)
    }

    /**
     * Sends one position for this tick: [heading] bent around obstacles and island edges. A step is only taken when
     * the block under the landing point AND under a point a little further ahead is solid ("probe ahead"), so the
     * bot stops at the rim instead of walking off it.
     */
    private fun move(heading: Double, speed: Double, yaw: Float, pitch: Float, now: Long) {
        // unstick: if we wanted to move but made no progress for 2 s, wander sideways for a moment
        if (now - progressAt > 2_000) {
            if (progressAt != 0L && hypot(bot.x - progressX, bot.z - progressZ) < 0.4) {
                wanderHeading = heading + (if (rnd.nextBoolean()) 1 else -1) * (PI / 2 + rnd.nextDouble())
                wanderUntil = now + 1_500
                preferredSide = -preferredSide
            }
            progressX = bot.x; progressZ = bot.z; progressAt = now
        }
        val offsets = doubleArrayOf(0.0, 0.5, -0.5, 1.0, -1.0, 1.6, -1.6, 2.3, -2.3)
        var edgeHit = false
        for (off in offsets) {
            val h = heading + off * preferredSide
            val nx = bot.x + cos(h) * speed
            val nz = bot.z + sin(h) * speed
            val aheadX = bot.x + cos(h) * (speed + PROBE_AHEAD)
            val aheadZ = bot.z + sin(h) * (speed + PROBE_AHEAD)
            val here = bot.terrain.floorAt(nx, nz, bot.y, 0, 2)
            val ahead = bot.terrain.floorAt(aheadX, aheadZ, bot.y, 0, 2)
            val hereKnown = bot.terrain.knows(nx, nz)
            val aheadKnown = bot.terrain.knows(aheadX, aheadZ)
            val ok = (here != null || !hereKnown) && (ahead != null || !aheadKnown)
            if (!ok) { if (hereKnown && here == null || aheadKnown && ahead == null) edgeHit = true; continue }
            if (off != 0.0) preferredSide = if (off > 0) preferredSide else -preferredSide
            bot.moveTo(nx, here ?: bot.y, nz, yaw, pitch)
            return
        }
        if (edgeHit) blockedByEdge.incrementAndGet()
        localBlockedAt = now
        bot.moveTo(bot.x, bot.y, bot.z, yaw, pitch)
    }

    // --- weapons -------------------------------------------------------------------------------------------------

    /** Sword up close, gun or staff at range, see [WeaponPolicy]. */
    private fun chooseWeapon(dist: Double, now: Long): Weapon? =
        WeaponPolicy.choose(loadout, dist, now, reloadBusyUntil, loadout.firstOrNull { it.slot == bot.selectedSlot })

    private fun act(enemy: Seen, weapon: Weapon?, dist: Double, now: Long, yaw: Float, pitch: Float, hasLos: Boolean) {
        val aimYaw = yaw + ((rnd.nextFloat() - 0.5f) * 2f * skill.aimErrorDeg)
        val aimPitch = pitch + ((rnd.nextFloat() - 0.5f) * skill.aimErrorDeg)
        if (weapon == null) {
            // Bare hands: still a real attack.
            if (dist <= 3.0 && now - lastSwingAt > skill.swingIntervalMs) { lastSwingAt = now; bot.punch(); bot.attack(enemy.id); swings.incrementAndGet(); noteAttack(now) }
            return
        }
        if (bot.selectedSlot != weapon.slot) {
            if (now - lastSwitchAt > 300) { lastSwitchAt = now; bot.selectHotbar(weapon.slot) }
            return
        }
        when (weapon.kind) {
            WeaponKind.MELEE -> {
                val energyOk = bot.food < 0 || bot.food >= MIN_MELEE_FOOD
                if (dist <= MELEE_REACH && energyOk && now - lastSwingAt > skill.swingIntervalMs) {
                    lastSwingAt = now
                    bot.punch()
                    bot.attack(enemy.id)
                    swings.incrementAndGet(); noteAttack(now)
                }
            }
            WeaponKind.RANGED -> {
                if (weapon.magazine && now < reloadBusyUntil) return
                val ammo = if (weapon.magazine) ammoOnBar() else null
                if (ammo == 0) { startReload(now); return }
                // Energy-cost casters (staffs) have no magazine: the food bar is their budget.
                val energyOk = weapon.magazine || bot.food < 0 || bot.food >= MIN_CAST_FOOD
                if (WeaponPolicy.mayFire(dist, hasLos) && energyOk && now - lastShotAt > skill.shotIntervalMs + rnd.nextInt(120)) {
                    lastShotAt = now
                    bot.useItem(aimYaw, aimPitch)
                    shots.incrementAndGet(); noteAttack(now)
                }
            }
        }
    }

    private fun ammoOnBar(): Int? {
        val text = bot.actionBars.lastOrNull()?.text ?: return null
        return AMMO.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun startReload(now: Long) {
        reloadStartedAt = now
        reloadBusyUntil = now + 2_300
        reloads.incrementAndGet()
        bot.sneak(true)
    }

    /** Shift+Q: sneak was pressed in [startReload]; drop key after 150 ms, release sneak after 400 ms. */
    private fun tickReload(now: Long) {
        if (reloadStartedAt == 0L) return
        val t = now - reloadStartedAt
        if (t >= 150 && reloadDropSent != reloadStartedAt) { reloadDropSent = reloadStartedAt; bot.dropSelected() }
        if (t >= 400) { bot.sneak(false); reloadStartedAt = 0L }
    }
    private var reloadDropSent = 0L

    /**
     * Extra item skills ([SkillUsePolicy], from the held item's own content bindings) while fighting with that item in hand,
     * melee and ranged alike (the primary swing / shot stays [act]'s loop). The
     * policy keeps the cooldowns; RIGHT_CLICK = useItem, LEFT_CLICK = punch + attack, SNEAK_RIGHT = sneak now and click
     * on a later tick (see [tickSkillCast]) so the sneak input reaches the server first.
     */
    private fun trySkill(weapon: Weapon?, dist: Double, hasLos: Boolean, now: Long, yaw: Float, pitch: Float) {
        if (weapon == null || bot.selectedSlot != weapon.slot) return
        if (skillSneakAt != 0L || now < reloadBusyUntil || reloadStartedAt != 0L) return
        if (weapon.kind == WeaponKind.RANGED && !weapon.magazine && bot.food in 0 until MIN_CAST_FOOD) return
        val b = (skillPolicies[weapon.slot] ?: return).decide(now, dist, hasLos) ?: return
        skillCasts.incrementAndGet()
        when (b.trigger) {
            SkillUsePolicy.Trigger.RIGHT_CLICK -> bot.useItem(yaw, pitch)
            SkillUsePolicy.Trigger.LEFT_CLICK -> { bot.punch(); lastSwingAt = now }
            SkillUsePolicy.Trigger.SNEAK_RIGHT -> { bot.sneak(true); skillSneakAt = now; skillYaw = yaw; skillPitch = pitch }
        }
    }

    /** Second half of a SNEAK_RIGHT cast: a tick after sneaking began, click, then release sneak. */
    private fun tickSkillCast(now: Long) {
        if (skillSneakAt == 0L || now <= skillSneakAt) return
        bot.useItem(skillYaw, skillPitch)
        bot.sneak(false)
        skillSneakAt = 0L
    }

    // --- loadout -------------------------------------------------------------------------------------------------

    /**
     * Learns what is in the hotbar: every filled slot 0..7 is selected once and the action bar that follows
     * tells ranged items (they show `Ammo n/m`) from melee ones. Rescanned when the hotbar contents change
     * (respawn, kit given) and while nothing usable was found.
     */
    private fun resolveLoadoutIfNeeded(now: Long) {
        val hotbar = bot.hotbar()
        val signature = hotbar.take(8).joinToString(",")
        if (signature == lastLoadoutSignature && (loadout.isNotEmpty() || now - lastLoadoutScanAt < 3_000)) return
        if (hotbar.take(8).all { it == -1 }) { lastLoadoutSignature = signature; loadout = emptyList(); lastLoadoutScanAt = now; return }
        lastLoadoutScanAt = now
        lastLoadoutSignature = signature
        val found = ArrayList<Weapon>()
        val kindByItem = HashMap<Int, Weapon>()
        healSlot = -1
        toolSlots.clear()
        for (slot in 0..7) {
            val item = hotbar[slot]
            if (item == -1) continue
            if (HEAL_LORE.containsMatchIn(bot.hotbarLore(slot).lowercase())) { healSlot = slot; continue }
            val known = kindByItem[item]
            if (known != null) { found += known.copy(slot = slot); continue }
            val lore = bot.hotbarLore(slot)
            val name = bot.hotbarName(slot)
            val c = LoadoutClassifier.classify(name, lore)
            when (c.kind) {
                LoadoutClassifier.Kind.TOOL -> { c.affinity?.let { toolSlots.putIfAbsent(it, slot) }; continue }
                LoadoutClassifier.Kind.OTHER -> {
                    // No tooltip at all (plain test items): learn it by probing the ammo counter; anything with a tooltip but no weapon binding is not a weapon.
                    if (lore.isBlank() && name.isNotBlank() && !name.lowercase().contains("compass")) {
                        val weapon = probeForAmmo(slot)
                        kindByItem[item] = weapon
                        found += weapon
                    }
                    continue
                }
                else -> {}
            }
            val weapon = Weapon(slot, if (c.kind == LoadoutClassifier.Kind.RANGED) WeaponKind.RANGED else WeaponKind.MELEE, magazine = c.magazine)
            kindByItem[item] = weapon
            found += weapon
        }
        loadout = found
        val catalog = itemSkills
        skillPolicies = if (catalog == null) emptyMap() else found.associate { it.slot to catalog.policyFor(bot.hotbarName(it.slot)) }
        lastLoadoutSignature = bot.hotbar().take(8).joinToString(",")
        // Start with the sword in hand if there is one, else the first ranged item (never a tool or the compass).
        (found.firstOrNull { it.kind == WeaponKind.MELEE } ?: found.firstOrNull())?.let { bot.selectHotbar(it.slot) }
    }

    /** Fallback for items without lore: select it and see whether the action bar shows an ammo counter. */
    private fun probeForAmmo(slot: Int): Weapon {
        val barsBefore = bot.actionBars.size
        bot.selectHotbar(slot)
        bot.waitUntil(1_200) { bot.actionBars.drop(barsBefore).any { AMMO.containsMatchIn(it.text) } }
        val ammo = bot.actionBars.drop(barsBefore).any { AMMO.containsMatchIn(it.text) }
        return Weapon(slot, if (ammo) WeaponKind.RANGED else WeaponKind.MELEE, magazine = ammo)
    }

    private companion object {
        /** Distance between slot origins (SlotAllocator pitch). */
        const val SLOT_PITCH = 2048.0
        val AMMO_WORD = Regex("reload|リロード")
        const val SIGHT_RANGE = 70.0
        const val CLICK_INTERVAL_MS = 500L
        const val CLICK_REACH = 5.0
        const val STRIKE_INTERVAL_MS = 450L
        const val TOOL_CAST_INTERVAL_MS = 2_000L
        const val STRIKE_REACH = 4.5
        const val NO_PATH_GIVE_UP_TICKS = 10
        const val STAR_BLOCK_ARRIVE = 2.2
        const val STAR_BLOCK_PAY_WINDOW_MS = 3_000L
        const val STAR_BLOCK_UNPAID_LIMIT = 2
        const val CACHE_PAY_WINDOW_MS = 2_000L
        const val CACHE_PAY_LOOKBACK_MS = 3_000L
        const val CACHE_RETRY_MS = 10_000L
        const val MELEE_REACH = 2.9
        const val EYE_HEIGHT = 1.62
        const val CHEST_HEIGHT = 1.0
        const val CHASE_ARRIVE = 2.5
        const val MIN_MELEE_FOOD = 4
        const val MIN_CAST_FOOD = 2
        const val PROBE_AHEAD = 0.8
        /** How far ahead of a travelling bot the floor must exist for it to jump. */
        const val JUMP_LOOKAHEAD = 2.5
        const val STAR_YIELD_RANGE = 12.0
        const val ECON_OPENING_MS = 30_000L
        const val ECON_SLICE_MS = 60_000L
        const val OBJECTIVE_SLICE_MS = 120_000L
        /** A pillar attacker's walk to the enemy pillar is long: after an economy slice it digs for this long before the next trip. */
        const val ATTACKER_OBJECTIVE_SLICE_MS = 240_000L
        const val LULL_MS = 10_000L
        /** An enemy farther than this does not pull a bot off its economy trip (unless it was hit within [ECON_HIT_MS]). */
        const val ECON_ENGAGE_RANGE = 16.0
        const val ECON_HIT_MS = 6_000L
        /** An attacker on the offence only visits a shop within this many blocks. */
        const val ECON_ATTACKER_SHOP_RANGE = 30.0
        /** A bot still unpaid this long after the match start stops waiting for its first star before an economy trip (the opening sweep owns the first stars; same as HUNGRY_MAX_MS). */
        const val ECON_STAR_FIRST_MS = 50_000L
        /** A star this close is picked up even on an economy trip. */
        const val ECON_STAR_DETOUR = 8.0
        const val KILL_DETOUR_RANGE = 4.0
        const val STAR_SIGHT_RANGE = 90.0
        /** How far a pillar attacker leaves its route for a visible star. */
        const val ATTACKER_STAR_RANGE = 14.0
        /** Preparation: stars and caches within this distance of the own spawn are the team's opening supply, swept first and split by team slot. */
        const val BASE_SWEEP_RADIUS = 45.0
        /** A bot without a star stops sweeping the base supply after this long and plays on as usual. */
        const val HUNGRY_MAX_MS = 50_000L
        const val NORMA_HOME_ARRIVE = 6.0
        val NORMA_READY = Regex("Norma goal met|ノルマ達成")
        const val STAR_CHASE_MS = 20_000L
        const val LOCAL_STEER_RANGE = 12.0
        const val GEN_COOLDOWN_MS = 12_000L
        /** Cache open radius is 2 blocks (content/generators/annihilation-cache.yaml); arrive inside it. */
        const val CACHE_ARRIVE = 1.3
        const val CACHE_DETOUR_RANGE = 28.0
        /** A cache bundle (about 4 stars) is worth a longer walk than a single spot star. */
        const val CACHE_VALUE_WEIGHT = 3.0
        const val GEN_DWELL_MS = 1_500L
        const val FALL_DEPTH = 12.0
        val HEAL_LORE = Regex("single use|使い切り")
        const val RETREAT_HP = 6f
        const val RECOVER_HP = 12f
        val AMMO = Regex("Ammo\\D*(\\d+)\\s*/\\s*(\\d+)")
    }
}
