package com.astarworks.astera.bots

import com.astarworks.astera.e2e.Bot
import com.astarworks.astera.e2e.EntityView
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode
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

/** A point of interest the bot walks to when no enemy is around (and digs at when it is a `core`). */
data class Objective(val name: String, val x: Double, val y: Double, val z: Double, val breakable: Boolean)

/** Mode knowledge for the brain: where to go when nobody is in sight. Implemented by [StageObjectives]. */
fun interface ObjectiveSource {
    /** The objective for a bot that spawned at ([spawnX], [spawnZ]), or null for "just look for enemies". */
    fun objectiveFor(spawnX: Double, spawnZ: Double): Objective?

    /** Star generator pedestals (x, z) of the stage, where stars wait to be picked up. */
    fun starPoints(): List<Pair<Double, Double>> = emptyList()

    fun routeGraph(): ArenaRouteGraph? = null

    /** Stage regions (stage-local coordinates) for [ModePlanner]; null = unknown. */
    fun zones(): ModePlanner.Zones? = null

    /** Team (`red`/`blue`) whose spawn is nearest ([spawnX], [spawnZ]) and every team of the stage. */
    fun ownTeamAt(spawnX: Double, spawnZ: Double): String? = null
    fun teams(): List<String> = emptyList()
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
    private val bot: Bot,
    private val skill: BotSkill = BotSkill(),
    private val objectives: ObjectiveSource? = null,
    /** Pick the Norma goal (`/astera norma choose star|kill`) from the situation. Off = keep whatever the server set. */
    private val chooseNormaGoal: Boolean = true,
    /** Game-mode id of the lane (lane id = mode id): selects the per-mode objective plan ([ModePlanner]). */
    private val modeId: String? = null,
) : AutoCloseable {

    enum class Mode { IDLE, FIGHT }

    private enum class Goal { STAR, KILL }

    private class Dest(val x: Double, val z: Double, val y: Double?, val arrive: Double, val kind: String, val starId: Int = -1, val zone: String = "")

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

    private val rnd = Random(bot.name.hashCode().toLong())
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
    private var objective: Objective? = null
    private var lastEnemyAt = 0L
    private var lastEnemyX = 0.0
    private var lastEnemyZ = 0.0
    private var wanderHeading = 0.0
    private var wanderUntil = 0L
    private var lastDigAt = 0L
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
    private val visitedAt = HashMap<Int, Long>()
    private val ignoredStars = HashMap<Int, Long>()
    private val starsSeen = HashMap<Int, DoubleArray>()
    private var starApproachId = -1
    private var starApproachSince = 0L
    private var highY = Double.NEGATIVE_INFINITY
    private var fellRecently = false
    /** Tick of the running travel jump (-1 = on the ground). */
    private var jumpStep = -1
    private var jumpDash = false
    private val stuck = StuckEscapePolicy()
    private val ineffective = IneffectiveTargets()
    private var planner: ModePlanner? = null
    private var sidestepUntil = 0L
    private var sidestepSign = 1
    private var forceJump = false
    private var lastBreakAt = 0L
    /** Escapes taken, for reports and tests. */
    val stuckEscapes = AtomicInteger()
    val blocksBroken = AtomicInteger()

    fun start(): BotBrain {
        exec.scheduleAtFixedRate({ runCatching { tick() }.onFailure { Bot.noteSink("bot ${bot.name}: brain error $it") } }, 500, 50, TimeUnit.MILLISECONDS)
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

    private fun tick() {
        val now = System.currentTimeMillis()
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
            planner?.onDeath(bot.x, bot.z)
            if (now - lastRespawnAt > 1_000) { lastRespawnAt = now; bot.respawn() }
            return
        }
        val gm = bot.gameMode
        if (gm == GameMode.SPECTATOR) {
            // Death cam: the server owns us until it respawns us. Stay quiet.
            endTravel()
            planner?.onDeath(bot.x, bot.z)
            idleNudge(now)
            return
        }
        if (!wasFighting) {
            wasFighting = true
            spawnX = bot.x; spawnZ = bot.z
            // Stages are authored around (0,0) and pasted into a slot on a fixed grid (ADR-0255): find that slot's origin.
            val originX = Math.round(spawnX / SLOT_PITCH) * SLOT_PITCH
            val originZ = Math.round(spawnZ / SLOT_PITCH) * SLOT_PITCH
            nav.originX = originX; nav.originZ = originZ
            objective = objectives?.objectiveFor(spawnX - originX, spawnZ - originZ)
                ?.let { it.copy(x = it.x + originX, z = it.z + originZ) }
            starPoints = objectives?.starPoints().orEmpty().map { (x, z) -> (x + originX) to (z + originZ) }
            fightStartedAt = now
            nav.clear()
            retreating = false
            stuck.reset()
            planner = buildPlanner(originX, originZ)
        }
        trackFall()
        if (now - lastTraceAt >= 2_500) {
            lastTraceAt = now
            if (trace.size < 60) trace += "${(now - fightStartedAt) / 1000}s %s (%.0f,%.0f)".format(lastKind, bot.x, bot.z)
        }
        resolveLoadoutIfNeeded(now)
        tickReload(now)

        val enemy = nearestEnemy(now)
        trackStars(now)
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
            when {
                weapon?.kind == WeaponKind.RANGED -> {
                    val range = skill.preferredRange
                    when {
                        flat > range + 2.5 -> Unit // approach along heading
                        flat < range - 3.5 -> heading += PI // back off
                        else -> { heading += strafeSign * PI / 2; speed *= 0.65 }
                    }
                }
                else -> {
                    if (flat <= 1.7) standStill = true
                    else if (flat < 3.2) { heading += strafeSign * 0.5; speed *= 0.8 }
                    speed = min(speed, (flat - 1.5).coerceAtLeast(0.0)).coerceAtLeast(if (standStill) 0.0 else 0.05)
                }
            }
            if (now > strafeUntil) { strafeSign = -strafeSign; strafeUntil = now + 1_000 + rnd.nextInt(1_600) }
            act(enemy, weapon, dist, now, yaw, pitch)
            lastKind = "fight>local flat=%.0f".format(flat)

            // Long way, a different height, a gap in the way, a star to fetch or a retreat: plan a route (A*) instead of steering.
            val needRoute = retreating || goForStar || flat > LOCAL_STEER_RANGE || abs(enemy.y - bot.y) > 3.0 ||
                (flat > 3.0 && now - localBlockedAt < 2_000)
            if (needRoute) {
                val d = when {
                    retreating -> Dest(spawnX, spawnZ, null, 3.0, "retreat")
                    goForStar -> starDest!!
                    else -> Dest(enemy.x, enemy.z, enemy.y, if (weapon?.kind == WeaponKind.RANGED) skill.preferredRange - 1.0 else 1.8, "enemy")
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
            val dest = chooseDest(now, starDest)
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
                when (st) {
                    Navigator.Status.MOVING -> { dwellOn(dest, now); return }
                    Navigator.Status.ARRIVED -> {
                        arrivedAt(dest, now)
                        objective?.takeIf { it.breakable && dest.kind == "objective" }?.let { digObjective(it, now) }
                        if (dest.kind == "mode" && modeBreakable) digObjective(Objective(dest.zone, dest.x, dest.y ?: bot.y, dest.z, true), now)
                    }
                    Navigator.Status.NO_PATH -> {
                        if (dest.kind == "mode") modePausedUntil = now + 5_000 // unreachable for now: patrol / roam meanwhile
                        if (dest.kind == "star") ignoreStar(dest.starId, now) else if (dest.kind != "enemy-seen") arrivedAt(dest, now, unreachable = true)
                    }
                }
                standStill = true
            }
        }

        if (standStill || heading == null) {
            bot.moveTo(bot.x, bot.y, bot.z, yaw, pitch)
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

    private fun nearestEnemy(now: Long): EntityView? {
        val myTeam = bot.teamOf(bot.name)?.first
        val candidates = ArrayList<TargetPolicy.Candidate>()
        for (e in bot.entities.values) {
            if (e.type != "PLAYER" || e.id == bot.selfEntityId) continue
            if (ineffective.ignored(e.id, now)) continue
            // No Tab-list entry (enemy team hidden from the list): no name, team or game mode - an unlisted player is
            // someone who is not on our team, and a dead one is caught by IneffectiveTargets (no hit lands).
            val name = bot.playerNames[e.uuid]
            candidates += TargetPolicy.Candidate(
                id = e.id, name = name ?: "?${e.uuid}", team = name?.let { bot.teamOf(it)?.first },
                spectating = bot.playerGameModes[e.uuid] == GameMode.SPECTATOR,
                x = e.x, y = e.y, z = e.z, listed = name != null,
            )
        }
        val pick = TargetPolicy.nearest(bot.name, myTeam, bot.x, bot.y, bot.z, candidates, SIGHT_RANGE) ?: return null
        return bot.entities[pick.id]
    }

    /**
     * Where to walk with nobody in sight: a star, a generator that may hold stars (STAR goal), a recent enemy
     * sighting, then the mode objective and a patrol of the stage's points of interest, else roam.
     */
    private fun chooseDest(now: Long, starDest: Dest?): Dest? {
        if (starDest != null) return starDest
        planner?.noteMarkers(bot.entities.values.filter { it.type == "BLOCK_DISPLAY" }.map { it.x to it.z })
        if (now >= modePausedUntil) planner?.step(now, bot.x, bot.z)?.let { st ->
            modeBreakable = st.breakable
            return Dest(st.x, st.z, st.y, st.arrive, "mode", zone = st.zone)
        }
        if (goal == Goal.STAR && starPoints.isNotEmpty()) {
            var bestI = -1
            var bestD = Double.MAX_VALUE
            for ((i, p) in starPoints.withIndex()) {
                if (now - (visitedAt[i] ?: -GEN_COOLDOWN_MS) < GEN_COOLDOWN_MS) continue
                val d = hypot(p.first - bot.x, p.second - bot.z)
                if (d < bestD) { bestD = d; bestI = i }
            }
            if (bestI >= 0) return Dest(starPoints[bestI].first, starPoints[bestI].second, null, 2.0, "gen", bestI)
        }
        if (now - lastEnemyAt < 8_000) return Dest(lastEnemyX, lastEnemyZ, null, 3.0, "enemy-seen")
        val patrol = patrolPoints()
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

    /** Enemy objective first, then the generator pedestals, then home: a loop that crosses the whole stage. */
    private fun patrolPoints(): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        objective?.let { out += (it.x + 0.5) to (it.z + 0.5) }
        out += starPoints
        return out
    }

    private fun arrivedAt(d: Dest, now: Long, unreachable: Boolean = false) {
        when (d.kind) {
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
                if (d.kind == "objective" && objective?.breakable == true && !unreachable) return
                if (dwellUntil == 0L) dwellUntil = now + (if (unreachable) 0 else 800)
                if (now >= dwellUntil) { patrolIndex++; dwellUntil = 0L }
            }
            "roam" -> wanderUntil = 0L
        }
    }

    private var starArrivedSince = 0L
    private var modeBreakable = false
    private var modePausedUntil = 0L

    private fun buildPlanner(originX: Double, originZ: Double): ModePlanner? {
        val id = modeId ?: return null
        val src = objectives ?: return null
        if (id !in ModePlanner.MODES) return null
        val base = src.zones() ?: return null
        val own = src.ownTeamAt(spawnX - originX, spawnZ - originZ) ?: return null
        val world = object : ModePlanner.Zones {
            override fun point(name: String) = base.point(name)?.let { Triple(it.first + originX, it.second, it.third + originZ) }
            override fun names(prefix: String) = base.names(prefix)
        }
        return ModePlanner(id, bot.name, own, src.teams().filter { it != own }, world).takeIf { it.handles() }
    }

    /**
     * Stuck escape for non-combat travel (see [StuckEscapePolicy]): hop, sidestep, re-plan around the blocked cell,
     * dig through. Returns true when this tick was spent on a sidestep.
     */
    private fun escapeStuck(dest: Dest, now: Long): Boolean {
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
        Thread({ runCatching { bot.dig(fx, y, fz, 400) } }, "bot-dig-${bot.name}").also { it.isDaemon = true }.start()
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

    private fun isStarItem(e: EntityView): Boolean {
        if (e.type != "ITEM") return false
        val texts = bot.entityTextsById[e.id]
        return texts == null || texts.isEmpty() || texts.any { it.contains('★') || it.contains("star", ignoreCase = true) }
    }

    /** The star pick-up to fetch: nearest dropped star item within reach (any distance with the STAR goal, a short detour with KILL). */
    private fun starTarget(now: Long): Dest? {
        val limit = if (goal == Goal.STAR) STAR_SIGHT_RANGE else KILL_DETOUR_RANGE
        var best: EntityView? = null
        var bestD = Double.MAX_VALUE
        for (e in bot.entities.values) {
            if (!isStarItem(e)) continue
            val until = ignoredStars[e.id]
            if (until != null && until > now) continue
            val d = hypot(e.x - bot.x, e.z - bot.z) + abs(e.y - bot.y) * 0.5
            if (d <= limit && d < bestD) { best = e; bestD = d }
        }
        return best?.let { Dest(it.x, it.z, null, 0.9, "star", it.id) }
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

    private fun updateRetreat(enemy: EntityView?, now: Long) {
        val hp = bot.health
        val enemyNear = enemy != null && hypot(enemy.x - bot.x, enemy.z - bot.z) < 14.0
        if (!retreating) {
            if (hp > 0f && hp <= RETREAT_HP && enemyNear) { retreating = true; retreatSince = now; nav.clear() }
        } else if (hp >= RECOVER_HP || now - retreatSince > 10_000 || (!enemyNear && now - lastEnemyAt > 3_000)) {
            retreating = false; nav.clear()
        }
    }

    /** STAR goal while the field is quiet, KILL while an enemy is on us; re-sent after the picker window resets it. */
    private fun updateGoal(enemy: EntityView?, now: Long) {
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

    private fun digObjective(o: Objective, now: Long) {
        if (now - lastDigAt < 1_500) return
        lastDigAt = now
        val x = Math.floor(o.x).toInt(); val y = Math.floor(o.y).toInt(); val z = Math.floor(o.z).toInt()
        Thread({ runCatching { bot.dig(x, y, z, 400) } }, "bot-dig-${bot.name}").also { it.isDaemon = true }.start()
    }

    // --- movement ------------------------------------------------------------------------------------------------

    /**
     * Non-combat travel (objective, stars, waypoints, roaming) sprint-jumps per [SprintJumpPolicy]: sets the sprint state
     * and the jump-arc [Bot.lift] that the next position packet (sent by [Navigator.follow]) carries. The floor pose
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

    private fun act(enemy: EntityView, weapon: Weapon?, dist: Double, now: Long, yaw: Float, pitch: Float) {
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
                if (dist <= RANGED_REACH && energyOk && now - lastShotAt > skill.shotIntervalMs + rnd.nextInt(120)) {
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
        for (slot in 0..7) {
            val item = hotbar[slot]
            if (item == -1) continue
            if (HEAL_LORE.containsMatchIn(bot.hotbarLore(slot).lowercase())) { healSlot = slot; continue }
            val known = kindByItem[item]
            if (known != null) { found += known.copy(slot = slot); continue }
            val lore = bot.hotbarLore(slot)
            val weapon = classifyByLore(lore)?.copy(slot = slot) ?: probeForAmmo(slot)
            kindByItem[item] = weapon
            found += weapon
        }
        loadout = found
        lastLoadoutSignature = bot.hotbar().take(8).joinToString(",")
        found.firstOrNull { it.kind == WeaponKind.MELEE }?.let { bot.selectHotbar(it.slot) }
    }

    /** Left-click bound = melee, only right-click bound = ranged (the item lore says which; en and ja). `null` = no lore. */
    private fun classifyByLore(lore: String): Weapon? {
        if (lore.isBlank()) return null
        val l = lore.lowercase()
        val left = Regex("(^|\\| )\\s*(left-click|左クリック)").containsMatchIn(l)
        val right = Regex("(^|\\| )\\s*(right-click|右クリック)").containsMatchIn(l)
        val reloadable = AMMO_WORD.containsMatchIn(l)
        return when {
            left -> Weapon(-1, WeaponKind.MELEE)
            right || reloadable -> Weapon(-1, WeaponKind.RANGED, magazine = reloadable)
            else -> Weapon(-1, WeaponKind.MELEE)
        }
    }

    /** Fallback for items without lore: select it and see whether the action bar shows an ammo counter. */
    private fun probeForAmmo(slot: Int): Weapon {
        val barsBefore = bot.actionBars.size
        bot.selectHotbar(slot)
        val end = System.currentTimeMillis() + 1_200
        while (System.currentTimeMillis() < end && bot.actionBars.drop(barsBefore).none { AMMO.containsMatchIn(it.text) }) Thread.sleep(30)
        val ammo = bot.actionBars.drop(barsBefore).any { AMMO.containsMatchIn(it.text) }
        return Weapon(slot, if (ammo) WeaponKind.RANGED else WeaponKind.MELEE, magazine = ammo)
    }

    private companion object {
        /** Distance between slot origins (SlotAllocator pitch). */
        const val SLOT_PITCH = 2048.0
        val AMMO_WORD = Regex("reload|リロード")
        const val SIGHT_RANGE = 70.0
        const val MELEE_REACH = 2.9
        const val RANGED_REACH = 40.0
        const val MIN_MELEE_FOOD = 4
        const val MIN_CAST_FOOD = 2
        const val PROBE_AHEAD = 0.8
        /** How far ahead of a travelling bot the floor must exist for it to jump. */
        const val JUMP_LOOKAHEAD = 2.5
        const val STAR_YIELD_RANGE = 12.0
        const val KILL_DETOUR_RANGE = 4.0
        const val STAR_SIGHT_RANGE = 90.0
        const val STAR_CHASE_MS = 20_000L
        const val LOCAL_STEER_RANGE = 12.0
        const val GEN_COOLDOWN_MS = 12_000L
        const val GEN_DWELL_MS = 1_500L
        const val FALL_DEPTH = 12.0
        val HEAL_LORE = Regex("single use|使い切り")
        const val RETREAT_HP = 6f
        const val RECOVER_HP = 12f
        val AMMO = Regex("Ammo\\D*(\\d+)\\s*/\\s*(\\d+)")
    }
}
