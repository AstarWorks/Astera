package com.astarworks.astera.gamesim.skillengine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Caster-view rule for held rig meshes, the sibling of load-time check 16 (caster-eye clutter). A `rig` op without `of:` draws the caster's weapon as an item display at the
 * caster's grip. Every row is visible to the caster (the owner-hidden design was removed by the founder, 2026-10-31), and the vanilla grip is about 0.95
 * blocks from the eye, so a distance-to-the-grip criterion measures nothing real. The real concern is that a held mesh swinging across the caster's
 * first-person view covers too much of the screen (club-slam / mace-smash covered about a quarter of the frame). So this test projects the mesh into the
 * caster's first-person camera and bounds the share of the frame it covers.
 *
 * Read from the committed goldens (`skill-oracle/<skill>/skill.txt`), which [ShippedV3ContentScenarioTest] keeps equal to the engine's output: the
 * `write` rows of every `kind=item item=main_hand` display give its world position, rotation and scale per tick (feet at the origin, eye at
 * 1.62 (`caster_eye.eye_height`), look from the pose's yaw / pitch). The mesh is the item display's flat
 * quad: scale x by scale y by [QUAD_THICKNESS] (a held weapon item is a thin plate; the model extent of content/models is not read). The camera is the
 * vanilla one: vertical FOV [FOV_DEG], 16:9. The share is the area of the projected plate inside the frame over the frame area, and it must stay at or
 * under [MAX_FRAME_SHARE] at every tick of every recorded pose.
 */
class CasterRigNearEyeScenarioTest : SkillEngineScenario() {

    @Test
    fun noHeldRigMeshCoversTheCasterView() {
        val goldens = repoRoot.resolve("tools/game-sim/src/test/resources/skill-oracle")
        val problems = Files.walk(goldens).use { s ->
            s.filter { it.toString().endsWith(".txt") }.sorted().toList().flatMap { check(goldens.relativize(it).toString().removeSuffix(".txt"), Files.readAllLines(it)) }
        }
        assertThat(problems).`as`("held rig meshes covering more than ${(MAX_FRAME_SHARE * 100).toInt()}% of the first-person frame").isEmpty()
    }

    private class Disp(var pos: DoubleArray, var rot: DoubleArray, var scale: DoubleArray)

    private fun check(key: String, lines: List<String>): List<String> {
        val problems = ArrayList<String>()
        var pose = ""
        var cam: Cam? = null
        val live = HashMap<String, Disp>() // display id -> state (held main_hand items only)
        val worst = HashMap<String, Pair<Int, Double>>() // op -> (tick, share)
        fun flush() {
            for ((op, w) in worst) if (w.second > MAX_FRAME_SHARE) problems += "$key [$pose] $op: the held rig mesh covers ${"%.0f".format(w.second * 100)}% of the first-person frame @${w.first} (budget ${(MAX_FRAME_SHARE * 100).toInt()}%)"
            live.clear(); worst.clear()
        }
        val ops = HashMap<String, String>()
        for (l in lines) {
            if (l.startsWith("## ")) {
                flush(); pose = l.removePrefix("## ")
                cam = POSE.find(pose)?.let { Cam(Math.toRadians(it.groupValues[1].toDouble()), Math.toRadians(it.groupValues[2].toDouble())) }
                continue
            }
            val c = cam ?: continue
            val m = ROW.find(l) ?: continue
            val tick = m.groupValues[1].toInt(); val kind = m.groupValues[2]; val id = m.groupValues[3]
            when (kind) {
                "remove" -> { live.remove(id); continue }
                "spawn" -> {
                    if (!l.contains("kind=item item=main_hand ")) continue
                    live[id] = Disp(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0, 1.0), doubleArrayOf(1.0, 1.0, 1.0))
                    ops[id] = Regex(" op=(\\S+)").find(l)!!.groupValues[1]
                }
            }
            val d = live[id] ?: continue
            POS.find(l)?.let { d.pos = doubleArrayOf(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }
            ROT.find(l)?.let { d.rot = doubleArrayOf(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble(), it.groupValues[4].toDouble()) }
            SCALE.find(l)?.let { d.scale = doubleArrayOf(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }
            if (kind != "write") continue
            val share = frameShare(c, d)
            val op = ops[id] ?: id
            val cur = worst[op]
            if (cur == null || share > cur.second) worst[op] = tick to share
        }
        flush()
        return problems
    }

    private class Cam(yaw: Double, pitch: Double) {
        val look = doubleArrayOf(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
        val right: DoubleArray
        val up: DoubleArray
        init {
            // right = worldUp x look, up = look x right (area is mirror independent)
            val r = doubleArrayOf(look[2], 0.0, -look[0]); val n = sqrt(r[0] * r[0] + r[2] * r[2]); right = doubleArrayOf(r[0] / n, 0.0, r[2] / n)
            up = doubleArrayOf(look[1] * right[2] - look[2] * right[1], look[2] * right[0] - look[0] * right[2], look[0] * right[1] - look[1] * right[0])
        }
    }

    /** Share of the 16:9 frame covered by the plate (convex polygon clipped to the near plane, projected, clipped to the frame). */
    private fun frameShare(c: Cam, d: Disp): Double {
        val hy = tan(Math.toRadians(FOV_DEG / 2)); val hx = hy * 16.0 / 9.0
        val corners = ArrayList<DoubleArray>()
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val local = doubleArrayOf(sx * 0.5 * d.scale[0], sy * 0.5 * d.scale[1], sz * 0.5 * QUAD_THICKNESS * d.scale[2])
            val w = rotate(d.rot, local)
            val rel = doubleArrayOf(d.pos[0] + w[0], d.pos[1] + w[1] - 1.62, d.pos[2] + w[2])
            corners += doubleArrayOf(dot(rel, c.right), dot(rel, c.up), dot(rel, c.look))
        }
        // near-plane clip over all box edges, then project the points in front
        val pts = ArrayList<DoubleArray>()
        for (p in corners) if (p[2] > NEAR) pts += p
        for (i in 0 until 8) for (j in i + 1 until 8) {
            if (Integer.bitCount(i xor j) != 1) continue
            val a = corners[i]; val b = corners[j]
            if ((a[2] > NEAR) != (b[2] > NEAR)) { val t = (NEAR - a[2]) / (b[2] - a[2]); pts += doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), NEAR) }
        }
        if (pts.size < 3) return 0.0
        val proj = pts.map { doubleArrayOf(it[0] / it[2] / hx, it[1] / it[2] / hy) } // frame = [-1,1]^2
        val hull = hull(proj)
        var poly = hull
        // clip to the frame rectangle
        for ((axis, sign) in listOf(0 to 1.0, 0 to -1.0, 1 to 1.0, 1 to -1.0)) poly = clip(poly, axis, sign)
        return area(poly) / 4.0
    }

    private fun clip(poly: List<DoubleArray>, axis: Int, sign: Double): List<DoubleArray> {
        if (poly.isEmpty()) return poly
        val out = ArrayList<DoubleArray>()
        fun inside(p: DoubleArray) = sign * p[axis] <= 1.0
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            if (inside(a)) out += a
            if (inside(a) != inside(b)) { val t = (sign - a[axis]) / (b[axis] - a[axis]); out += doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])) }
        }
        return out
    }

    private fun hull(points: List<DoubleArray>): List<DoubleArray> {
        val p = points.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (p.size < 3) return p
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<DoubleArray>(); for (q in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeLast(); lower += q }
        val upper = ArrayList<DoubleArray>(); for (q in p.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeLast(); upper += q }
        lower.removeLast(); upper.removeLast()
        return lower + upper // counter-clockwise
    }

    private fun area(poly: List<DoubleArray>): Double {
        var s = 0.0
        for (i in poly.indices) { val a = poly[i]; val b = poly[(i + 1) % poly.size]; s += a[0] * b[1] - b[0] * a[1] }
        return abs(s) / 2
    }

    private fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
        val (x, y, z, w) = q
        val tx = 2 * (y * v[2] - z * v[1]); val ty = 2 * (z * v[0] - x * v[2]); val tz = 2 * (x * v[1] - y * v[0])
        return doubleArrayOf(v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private companion object {
        /** Share of the first-person frame a held mesh may cover (the club-slam / mace-smash quarter-of-the-frame complaint). */
        const val MAX_FRAME_SHARE = 0.25
        const val FOV_DEG = 70.0
        const val NEAR = 0.05
        /** Depth of the item display's plate relative to its scale (a flat weapon item is 1/16 thick). */
        const val QUAD_THICKNESS = 0.0625
        val POSE = Regex("^(?:pose|pose-hit) yaw=(-?[\\d.]+) pitch=(-?[\\d.]+)")
        val ROW = Regex("^@(\\d+) (spawn|write|remove) #(\\d+) ")
        val POS = Regex(" pos=(\\S+),(\\S+),(\\S+) ")
        val ROT = Regex(" rot=(\\S+),(\\S+),(\\S+),(\\S+) ")
        val SCALE = Regex(" scale=(\\S+),(\\S+),(\\S+) ")
    }
}
