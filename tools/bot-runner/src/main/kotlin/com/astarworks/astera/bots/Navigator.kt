package com.astarworks.astera.bots

import com.astarworks.astera.e2e.Bot
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Walks a bot along [PathFinder] paths, one position packet per tick (ADR-0247). It caches the path and only searches
 * again when it must: no path yet, the goal moved, the path ran out short of the goal (capped search), the bot made no
 * progress for 1.5 s (the cell in front is then blocked for a while), the next cell is no longer walkable, or the bot
 * was pushed/teleported away from the path. Searches are throttled so a chasing bot costs one search per ~0.6 s.
 */
class Navigator(private val terrain: Terrain, private val graph: ArenaRouteGraph? = null) {
    private val waypoints = ArrayDeque<ArenaRouteGraph.Point>()
    private var routeGx = Double.NaN
    private var routeGz = Double.NaN

    enum class Status { ARRIVED, MOVING, NO_PATH }

    private val finder = PathFinder(terrain)
    private var cells: List<PathFinder.Cell> = emptyList()
    private var idx = 0
    private var complete = false
    private var planGx = Double.NaN
    private var planGz = Double.NaN
    private var plannedAt = 0L
    private var noPathUntil = 0L
    private var stuckX = 0.0
    private var stuckZ = 0.0
    private var stuckAt = 0L
    private val blocked = HashMap<Long, Long>()

    val searches = AtomicInteger()
    val stuckReplans = AtomicInteger()
    val expandedTotal = AtomicInteger()
    @Volatile var maxSearchMicros = 0L; private set

    private fun clearLocal() { cells = emptyList(); idx = 0; planGx = Double.NaN; noPathUntil = 0L }

    fun clear() { clearLocal(); waypoints.clear(); routeGx = Double.NaN }

    /** Moves one tick towards ([gx], [gz]) until within [arrive] blocks horizontally. [yaw] null = face the movement. */
    fun follow(bot: Bot, gx: Double, gz: Double, gy: Double?, arrive: Double, speed: Double, now: Long, yaw: Float?, pitch: Float): Status {
        if (hypot(gx - bot.x, gz - bot.z) <= arrive && (gy == null || abs(gy - bot.y) <= 2.5)) { clear(); return Status.ARRIVED }
        if (routeGx.isNaN() || hypot(gx - routeGx, gz - routeGz) > 3.0) {
            waypoints.clear()
            if (hypot(gx - bot.x, gz - bot.z) > 48.0) waypoints.addAll(graph?.route(bot.x, bot.z, gx, gz).orEmpty())
            routeGx = gx; routeGz = gz
            clearLocal()
        }
        while (waypoints.isNotEmpty()) {
            val next = waypoints.first()
            if (hypot(next.x - bot.x, next.z - bot.z) > 3.0) break
            waypoints.removeFirst()
            clearLocal()
        }
        val next = waypoints.firstOrNull()
        return if (next == null) followLocal(bot, gx, gz, gy, arrive, speed, now, yaw, pitch)
        else followLocal(bot, next.x, next.z, next.y, 2.0, speed, now, yaw, pitch)
    }

    private fun followLocal(bot: Bot, gx: Double, gz: Double, gy: Double?, arrive: Double, speed: Double, now: Long, yaw: Float?, pitch: Float): Status {
        if (hypot(gx - bot.x, gz - bot.z) <= arrive && (gy == null || abs(gy - bot.y) <= 2.5)) { clear(); return Status.ARRIVED }

        if (stuckAt == 0L) { stuckX = bot.x; stuckZ = bot.z; stuckAt = now }
        if (now - stuckAt > 1_500) {
            if (cells.isNotEmpty() && idx < cells.size && hypot(bot.x - stuckX, bot.z - stuckZ) < 0.5) {
                val c = cells[idx]
                blocked[PathFinder.key(c.x, c.y, c.z)] = now + 8_000
                stuckReplans.incrementAndGet()
                cells = emptyList()
            }
            stuckX = bot.x; stuckZ = bot.z; stuckAt = now
        }

        val goalMoved = planGx.isNaN() || hypot(gx - planGx, gz - planGz) > 3.0
        var need = cells.isEmpty() || (goalMoved && now - plannedAt > 600)
        if (!need && idx >= cells.size) need = !complete || hypot(gx - bot.x, gz - bot.z) > arrive + 1.0
        if (!need && idx < cells.size) {
            val c = cells[idx]
            finder.clearCache()
            if (!finder.standable(c.x, c.y, c.z)) need = true
            else if (hypot(c.x + 0.5 - bot.x, c.z + 0.5 - bot.z) > 5.0) need = true // pushed or teleported off the path
        }
        if (need) {
            if (now < noPathUntil) return Status.NO_PATH
            if (!plan(bot, gx, gz, gy, arrive, now)) return Status.NO_PATH
        }
        if (idx >= cells.size) return Status.NO_PATH

        val c = cells[idx]
        val prev = cells.getOrNull(idx - 1)
        val tx = c.x + 0.5
        val tz = c.z + 0.5
        val dx = tx - bot.x
        val dz = tz - bot.z
        val d = hypot(dx, dz)
        val step = min(speed, d)
        val nx = if (d > 1e-6) bot.x + dx / d * step else bot.x
        val nz = if (d > 1e-6) bot.z + dz / d * step else bot.z
        val py = prev?.y?.toDouble() ?: bot.y
        val segLen = if (prev != null) hypot(tx - (prev.x + 0.5), tz - (prev.z + 0.5)).coerceAtLeast(1.0) else 1.0
        val frac = if (prev != null) (1.0 - d / segLen).coerceIn(0.0, 1.0) else 1.0
        val ny = when {
            c.y.toDouble() == py -> c.y.toDouble()
            c.y > py -> if (frac >= 0.3) c.y.toDouble() else py
            else -> if (frac >= 0.5) c.y.toDouble() else py
        }
        val faceYaw = yaw ?: Math.toDegrees(atan2(-dx, dz)).toFloat()
        if (d <= speed + 0.02) idx++
        bot.moveTo(nx, ny, nz, faceYaw, pitch)
        return Status.MOVING
    }

    private fun plan(bot: Bot, gx: Double, gz: Double, gy: Double?, arrive: Double, now: Long): Boolean {
        blocked.entries.removeIf { it.value < now }
        finder.clearCache()
        val start = finder.cellAt(bot.x, bot.y, bot.z)
        plannedAt = now
        planGx = gx; planGz = gz
        if (start == null) { cells = emptyList(); noPathUntil = now + 500; return false }
        val t0 = System.nanoTime()
        val res = finder.find(start[0], start[1], start[2], gx, gz, gy, arrive, blocked.keys)
        val micros = (System.nanoTime() - t0) / 1_000
        if (micros > maxSearchMicros) maxSearchMicros = micros
        searches.incrementAndGet()
        if (res == null) { cells = emptyList(); noPathUntil = now + 800; return false }
        expandedTotal.addAndGet(res.expanded)
        cells = res.cells
        complete = res.complete
        idx = if (cells.size > 1) 1 else 1
        if (cells.size <= 1) { noPathUntil = now + 500; return false }
        return true
    }
}
