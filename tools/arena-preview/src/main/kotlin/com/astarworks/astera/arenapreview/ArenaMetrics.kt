package com.astarworks.astera.arenapreview

import com.astarworks.astera.bots.ArenaRouteGraph
import com.astarworks.astera.bots.PathFinder
import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan.Companion.isPassable
import com.astarworks.astera.core.domain.stage.Stage
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Terrain-relief measurements over the generated block model (founder feedback: "so big, yet totally flat").
 * Ground = the standable top of each column below `surfaceY + SCAN`; spires and tall castle shafts are cut off by the
 * scan ceiling, so the numbers describe what a player walks on and hides behind.
 */
object ArenaMetrics {
    private const val SCAN = 12
    private const val SPRINT = 5.6

    class Result(
        val footX: Int, val footZ: Int, val area: Int,
        val groundMin: Int, val groundMax: Int, val p2: Int, val p98: Int, val stdDev: Double,
        val flatShare: Double, val meanSight: Double, val longSight: Double,
        val cover: Int, val contactSeconds: Double?, val contactBlocks: Int?,
    ) {
        fun row(id: String) = "| $id | ${footX}x$footZ | $area | $groundMin..$groundMax (p2-p98 $p2..$p98) | %.2f | %.0f%% | %.1f | %.0f%% | $cover | %s |".format(
            stdDev, flatShare * 100, meanSight, longSight * 100,
            if (contactSeconds == null) "-" else "%.1f s (%d blocks)".format(contactSeconds, contactBlocks),
        )
    }

    const val HEADER = "| Stage | Footprint x*z | Land columns | Ground y (min..max) | Height std-dev | Flat ground | Mean free run | Rays >= 30 | Cover clusters | Spawn-to-midpoint (sprint) |\n|---|---|---:|---|---:|---:|---:|---:|---:|---|\n"

    private fun solidBlock(state: String?): Boolean = state != null && !isPassable(state)

    fun ground(plan: ArenaBuildPlan): Map<Long, Int> {
        val s = plan.surfaceY
        val out = HashMap<Long, Int>()
        for (pos in plan.blocks.keys) {
            if (pos.y < s - 8 || pos.y > s + SCAN) continue
            val k = key(pos.x, pos.z)
            if (k in out) continue
            for (y in s + SCAN downTo s - 8) {
                val here = plan.stateAt(pos.x, y, pos.z)
                if (!solidBlock(here) || treeLike(here!!)) continue
                out[k] = y
                break
            }
        }
        return out
    }

    private fun treeLike(state: String): Boolean = state.contains("_leaves") || state.contains("_log") || state.contains("_stem") || state.contains("_wall[") || state.endsWith("_wall") || state.contains("lantern")

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    private fun kx(k: Long) = (k shr 32).toInt()
    private fun kz(k: Long) = k.toInt()

    fun measure(stage: Stage, plan: ArenaBuildPlan, finder: PathFinder, graph: ArenaRouteGraph): Result {
        val s = plan.surfaceY
        val g = ground(plan)
        val keys = g.keys.toList()
        val ys = g.values.sorted()
        val mean = ys.average()
        val std = sqrt(ys.sumOf { (it - mean) * (it - mean) } / ys.size)
        val flat = keys.count { k ->
            val gy = g[k]!!
            var ok = true
            for (dx in -2..2) for (dz in -2..2) if (g[key(kx(k) + dx, kz(k) + dz)] != gy) ok = false
            ok && (1..3).none { solidBlock(plan.stateAt(kx(k), gy + it, kz(k))) }
        }
        // Sightlines: 16 rays at eye height from a deterministic column sample; blocked by terrain or cover, void ends the run.
        var rays = 0; var total = 0.0; var long = 0
        val sample = keys.sorted().filterIndexed { i, _ -> i % maxOf(1, keys.size / 400) == 0 }
        for (k in sample) {
            val eye = g[k]!! + 2
            for (r in 0 until 16) {
                val a = r * PI / 8
                var len = 0
                for (step in 1..80) {
                    val x = floor(kx(k) + cos(a) * step).toInt(); val z = floor(kz(k) + sin(a) * step).toInt()
                    val gy = g[key(x, z)] ?: break
                    if (gy >= eye || solidBlock(plan.stateAt(x, eye, z)) || solidBlock(plan.stateAt(x, eye - 1, z))) break
                    len = step
                }
                rays++; total += len; if (len >= 30) long++
            }
        }
        // Cover clusters: columns standing >= 2 above the local ground (box blur r=5) or carrying a full block above ground.
        val raised = HashSet<Long>()
        for (k in keys) {
            val gy = g[k]!!
            var sum = 0; var n = 0
            for (dx in -5..5 step 2) for (dz in -5..5 step 2) g[key(kx(k) + dx, kz(k) + dz)]?.let { sum += it; n++ }
            val blur = sum.toDouble() / n
            val obstacle = solidBlock(plan.stateAt(kx(k), gy + 1, kz(k))) && !plan.stateAt(kx(k), gy + 1, kz(k))!!.contains("_leaves")
            if (gy - blur >= 2.0 || obstacle) raised += k
        }
        val seen = HashSet<Long>(); var clusters = 0
        for (k in raised) {
            if (k in seen) continue
            var size = 0; val stack = ArrayDeque<Long>(); stack.add(k); seen += k
            while (stack.isNotEmpty()) {
                val c = stack.removeLast(); size++
                for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val n = key(kx(c) + dx, kz(c) + dz)
                    if (n in raised && seen.add(n)) stack.add(n)
                }
            }
            if (size >= 3) clusters++
        }
        // Time to contact: shortest walk from a red/first-team spawn to the next team's spawn, halved (both sides run).
        var seconds: Double? = null; var blocks: Int? = null
        val spawns = stage.spawnPoints.filter { it.team != null }.groupBy { it.team }.values.map { it.first().position }
        if (spawns.size >= 2) {
            val a = spawns[0]; val b = spawns[1]
            val len = walkLength(plan, finder, graph, a.x, a.y, a.z, b.x, b.z)
            if (len != null) { blocks = len / 2; seconds = blocks / SPRINT }
        }
        return Result(
            plan.maxX - plan.minX + 1, plan.maxZ - plan.minZ + 1, g.size,
            ys.first(), ys.last(), ys[(ys.size * 0.02).toInt()], ys[((ys.size - 1) * 0.98).toInt()], std,
            flat.toDouble() / keys.size, total / rays, long.toDouble() / rays, clusters, seconds, blocks,
        )
    }

    private fun walkLength(plan: ArenaBuildPlan, finder: PathFinder, graph: ArenaRouteGraph, sx: Double, sy: Double, sz: Double, gx: Double, gz: Double): Int? {
        var x = floor(sx).toInt(); var y = floor(sy).toInt(); var z = floor(sz).toInt(); var cells = 0
        val far = hypot(gx - sx, gz - sz) > 48.0
        val legs = (if (far) graph.route(sx, sz, gx, gz).map { Triple(it.x, it.z, it.y) } else emptyList()) + Triple(gx, gz, null as Double?)
        for ((i, leg) in legs.withIndex()) {
            var replans = 0
            while (true) {
                val r = finder.find(x, y, z, leg.first, leg.second, leg.third, if (i == legs.lastIndex) 2.0 else 2.0) ?: return null
                cells += r.cells.size
                val end = r.cells.last()
                val moved = end.x != x || end.y != y || end.z != z
                x = end.x; y = end.y; z = end.z
                if (r.complete) break
                if (!moved || ++replans > 12) return null
            }
        }
        return cells
    }

    /** Height map picture: ground y as a colour ramp (blue low, green at the surface, to white high), with 1-block contour darkening. */
    fun heightmap(stage: Stage, plan: ArenaBuildPlan): BufferedImage {
        val g = ground(plan)
        val s = plan.surfaceY
        val scale = 3
        val img = BufferedImage((plan.maxX - plan.minX + 1) * scale + 120, (plan.maxZ - plan.minZ + 1) * scale + 60, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.color = Color(0x161c30); gr.fillRect(0, 0, img.width, img.height)
        gr.color = Color.WHITE; gr.drawString("${stage.id.value} heightmap (surface y=$s): blue < surface < green < yellow < red < white", 10, 18)
        for ((k, y) in g) {
            val rel = y - s
            val c = when {
                rel < 0 -> Color(40, (80 + 30 * rel).coerceAtLeast(30), 200)
                rel == 0 -> Color(60, 150, 70)
                else -> Color.getHSBColor((0.30f - 0.03f * rel).coerceAtLeast(0f), 0.8f, 1.0f)
            }
            gr.color = c
            gr.fillRect(10 + (kx(k) - plan.minX) * scale, 30 + (kz(k) - plan.minZ) * scale, scale, scale)
        }
        // legend
        for (i in -4..12) { gr.color = when { i < 0 -> Color(40, (80 + 30 * i).coerceAtLeast(30), 200); i == 0 -> Color(60, 150, 70); else -> Color.getHSBColor((0.30f - 0.03f * i).coerceAtLeast(0f), 0.8f, 1f) }
            gr.fillRect(img.width - 100, 30 + (i + 4) * 12, 14, 12); gr.color = Color.WHITE; gr.drawString("${if (i >= 0) "+" else ""}$i", img.width - 80, 40 + (i + 4) * 12) }
        gr.dispose()
        return img
    }
}
