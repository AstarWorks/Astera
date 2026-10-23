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

data class Weapon(val slot: Int, val kind: WeaponKind)

/** A point of interest the bot walks to when no enemy is around (and digs at when it is a `core`). */
data class Objective(val name: String, val x: Double, val y: Double, val z: Double, val breakable: Boolean)

/** Mode knowledge for the brain: where to go when nobody is in sight. Implemented by [StageObjectives]. */
fun interface ObjectiveSource {
    /** The objective for a bot that spawned at ([spawnX], [spawnZ]), or null for "just look for enemies". */
    fun objectiveFor(spawnX: Double, spawnZ: Double): Objective?
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
) : AutoCloseable {

    enum class Mode { IDLE, FIGHT }

    @Volatile var mode: Mode = Mode.IDLE

    /** Counters for reports and tests. */
    val swings = AtomicInteger()
    val shots = AtomicInteger()
    val reloads = AtomicInteger()
    val ticksWithEnemy = AtomicInteger()
    val blockedByEdge = AtomicInteger()

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

    fun start(): BotBrain {
        exec.scheduleAtFixedRate({ runCatching { tick() }.onFailure { Bot.noteSink("bot ${bot.name}: brain error $it") } }, 500, 50, TimeUnit.MILLISECONDS)
        return this
    }

    override fun close() {
        mode = Mode.IDLE
        exec.shutdownNow()
    }

    fun summary(): String = "swings=${swings.get()} shots=${shots.get()} reloads=${reloads.get()} enemyTicks=${ticksWithEnemy.get()} edgeBlocks=${blockedByEdge.get()} goal=${objective?.name} loadout=$loadout"

    // --- the tick ------------------------------------------------------------------------------------------------

    private fun tick() {
        val now = System.currentTimeMillis()
        if (!bot.joined) return
        if (mode != Mode.FIGHT) {
            wasFighting = false
            idleNudge(now)
            return
        }
        if (bot.health == 0f) {
            if (now - lastRespawnAt > 1_000) { lastRespawnAt = now; bot.respawn() }
            return
        }
        val gm = bot.gameMode
        if (gm == GameMode.SPECTATOR) {
            // Death cam: the server owns us until it respawns us. Stay quiet.
            idleNudge(now)
            return
        }
        if (!wasFighting) {
            wasFighting = true
            spawnX = bot.x; spawnZ = bot.z
            objective = objectives?.objectiveFor(spawnX, spawnZ)
        }
        resolveLoadoutIfNeeded(now)
        tickReload(now)

        val enemy = nearestEnemy()
        var yaw = bot.yawDeg
        var pitch = bot.pitchDeg
        var speed = skill.walkSpeed
        var heading: Double? = null // world-space direction of travel, radians in (x, z)
        var standStill = false

        if (enemy != null) {
            ticksWithEnemy.incrementAndGet()
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
        } else {
            val goal = chooseGoal(now)
            if (goal != null) {
                val dx = goal.first - bot.x
                val dz = goal.second - bot.z
                val flat = hypot(dx, dz)
                if (flat > goal.third) {
                    heading = atan2(dz, dx)
                    yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
                    pitch = 0f
                } else {
                    standStill = true
                    objective?.takeIf { it.breakable }?.let { digObjective(it, now) }
                }
            } else {
                standStill = true
            }
        }

        if (standStill || heading == null) {
            bot.moveTo(bot.x, bot.y, bot.z, yaw, pitch)
        } else {
            move(heading, speed, yaw, pitch, now)
        }
    }

    private fun idleNudge(now: Long) {
        if (now - lastIdleNudgeAt < 1_000) return
        lastIdleNudgeAt = now
        bot.nudge()
    }

    // --- target selection ------------------------------------------------------------------------------------------

    private fun nearestEnemy(): EntityView? {
        val myTeam = bot.teamOf(bot.name)?.first
        var best: EntityView? = null
        var bestD = Double.MAX_VALUE
        for (e in bot.entities.values) {
            if (e.type != "PLAYER" || e.id == bot.selfEntityId) continue
            val name = bot.playerNames[e.uuid] ?: continue
            if (name == bot.name) continue
            if (myTeam != null && bot.teamOf(name)?.first == myTeam) continue
            val d = hypot(e.x - bot.x, e.z - bot.z) + abs(e.y - bot.y) * 0.5
            if (d < bestD && d <= SIGHT_RANGE) { best = e; bestD = d }
        }
        return best
    }

    /** Where to walk with nobody in sight: a recent enemy sighting, the mode objective, else roam. Third = arrive radius. */
    private fun chooseGoal(now: Long): Triple<Double, Double, Double>? {
        if (now - lastEnemyAt < 8_000) return Triple(lastEnemyX, lastEnemyZ, 3.0)
        objective?.let { return Triple(it.x + 0.5, it.z + 0.5, 3.5) }
        if (now > wanderUntil) {
            wanderHeading = rnd.nextDouble() * 2 * PI
            wanderUntil = now + 2_500 + rnd.nextInt(3_000)
        }
        return Triple(bot.x + cos(wanderHeading) * 20, bot.z + sin(wanderHeading) * 20, 1.0)
    }

    private fun digObjective(o: Objective, now: Long) {
        if (now - lastDigAt < 1_500) return
        lastDigAt = now
        val x = Math.floor(o.x).toInt(); val y = Math.floor(o.y).toInt(); val z = Math.floor(o.z).toInt()
        Thread({ runCatching { bot.dig(x, y, z, 400) } }, "bot-dig-${bot.name}").also { it.isDaemon = true }.start()
    }

    // --- movement ------------------------------------------------------------------------------------------------

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
        bot.moveTo(bot.x, bot.y, bot.z, yaw, pitch)
    }

    // --- weapons -------------------------------------------------------------------------------------------------

    /** Melee up close (or while the magazine reloads), the ranged item at distance. */
    private fun chooseWeapon(dist: Double, now: Long): Weapon? {
        val melee = loadout.firstOrNull { it.kind == WeaponKind.MELEE }
        val ranged = loadout.firstOrNull { it.kind == WeaponKind.RANGED }
        return when {
            ranged != null && melee != null -> if (dist <= MELEE_SWITCH || now < reloadBusyUntil && dist <= 8.0) melee else ranged
            ranged != null -> ranged
            else -> melee
        }
    }

    private fun act(enemy: EntityView, weapon: Weapon?, dist: Double, now: Long, yaw: Float, pitch: Float) {
        val aimYaw = yaw + ((rnd.nextFloat() - 0.5f) * 2f * skill.aimErrorDeg)
        val aimPitch = pitch + ((rnd.nextFloat() - 0.5f) * skill.aimErrorDeg)
        if (weapon == null) {
            // Bare hands: still a real attack.
            if (dist <= 3.0 && now - lastSwingAt > skill.swingIntervalMs) { lastSwingAt = now; bot.punch(); bot.attack(enemy.id); swings.incrementAndGet() }
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
                    swings.incrementAndGet()
                }
            }
            WeaponKind.RANGED -> {
                if (now < reloadBusyUntil) return
                val ammo = ammoOnBar()
                if (ammo == 0) { startReload(now); return }
                // Energy-cost casters (staffs) have no magazine: the food bar is their budget.
                val energyOk = ammo != null || bot.food < 0 || bot.food >= MIN_CAST_FOOD
                if (dist <= RANGED_REACH && energyOk && now - lastShotAt > skill.shotIntervalMs + rnd.nextInt(120)) {
                    lastShotAt = now
                    bot.useItem(aimYaw, aimPitch)
                    shots.incrementAndGet()
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
        if (t in 150..199 && reloadDropSent != reloadStartedAt) { reloadDropSent = reloadStartedAt; bot.dropSelected() }
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
        val kindByItem = HashMap<Int, WeaponKind>()
        for (slot in 0..7) {
            val item = hotbar[slot]
            if (item == -1) continue
            val known = kindByItem[item]
            if (known != null) { found += Weapon(slot, known); continue }
            val lore = bot.hotbarLore(slot)
            val kind = classifyByLore(lore) ?: probeForAmmo(slot)
            kindByItem[item] = kind
            found += Weapon(slot, kind)
        }
        loadout = found
        lastLoadoutSignature = bot.hotbar().take(8).joinToString(",")
        found.firstOrNull { it.kind == WeaponKind.MELEE }?.let { bot.selectHotbar(it.slot) }
    }

    /** Left-click bound = melee, only right-click bound = ranged (the item lore says which; en and ja). `null` = no lore. */
    private fun classifyByLore(lore: String): WeaponKind? {
        if (lore.isBlank()) return null
        val l = lore.lowercase()
        val left = Regex("(^|\\| )\\s*(left-click|左クリック)").containsMatchIn(l)
        val right = Regex("(^|\\| )\\s*(right-click|右クリック)").containsMatchIn(l)
        return when {
            left -> WeaponKind.MELEE
            right || AMMO_WORD.containsMatchIn(l) -> WeaponKind.RANGED
            else -> WeaponKind.MELEE
        }
    }

    /** Fallback for items without lore: select it and see whether the action bar shows an ammo counter. */
    private fun probeForAmmo(slot: Int): WeaponKind {
        val barsBefore = bot.actionBars.size
        bot.selectHotbar(slot)
        val end = System.currentTimeMillis() + 1_200
        while (System.currentTimeMillis() < end && bot.actionBars.drop(barsBefore).none { AMMO.containsMatchIn(it.text) }) Thread.sleep(30)
        return if (bot.actionBars.drop(barsBefore).any { AMMO.containsMatchIn(it.text) }) WeaponKind.RANGED else WeaponKind.MELEE
    }

    private companion object {
        val AMMO_WORD = Regex("reload|リロード")
        const val SIGHT_RANGE = 70.0
        const val MELEE_REACH = 2.9
        const val MELEE_SWITCH = 4.0
        const val RANGED_REACH = 40.0
        const val MIN_MELEE_FOOD = 4
        const val MIN_CAST_FOOD = 2
        const val PROBE_AHEAD = 0.8
        val AMMO = Regex("Ammo\\D*(\\d+)\\s*/\\s*(\\d+)")
    }
}
