package com.astarworks.astera.gamesim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * SKL-14: the per-tick rotation and scale of a skill's VFX displays, with the frame orient (v1 `play_vfx orient`, VfxPlayer :240
 * -> VfxOrient.frame) and frame scale (v1 `play_vfx scale`, VfxTimeline :102 / :213 / :360-369) applied, against the numbers the
 * v1 engine put on the wire (`vanilla-parity/frame-orient-v1.tsv`, taken from the v1 vanilla dump). A row is one block display
 * (by spawn order among the skill's blocks), one tick: v1's effective rotation (entity yaw / pitch x left rotation) and scale.
 * The cast is the real shipped skill through the real wiring (`VanillaDump`); the pairing and the tolerances (3 degrees, 5%)
 * are the ones `tools/skill-convert/vanilla_diff.py` uses.
 */
class FrameOrientScaleV1ParityScenarioTest {

    private class Disp(val spawnTick: Int, val spawnId: Int) {
        var alive = true
        var yaw = 0.0
        var pitch = 0.0
        var left = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        var scale = doubleArrayOf(1.0, 1.0, 1.0)
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    private fun axis(x: Double, y: Double, z: Double, deg: Double): DoubleArray {
        val h = Math.toRadians(deg) / 2
        return doubleArrayOf(x * sin(h), y * sin(h), z * sin(h), cos(h))
    }

    /** Minecraft entity rotation (yaw about -Y, pitch about X) times the display's left rotation. */
    private fun effective(d: Disp) = mul(mul(axis(0.0, 1.0, 0.0, -d.yaw), axis(1.0, 0.0, 0.0, d.pitch)), d.left)

    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val dot = abs(a.indices.sumOf { a[it] * b[it] }).coerceAtMost(1.0)
        return Math.toDegrees(2 * acos(dot))
    }

    private fun scaleDiff(a: DoubleArray, b: DoubleArray): Double {
        var m = 0.0
        for (i in 0..2) {
            val big = max(abs(a[i]), abs(b[i]))
            if (big < 1e-4) continue
            m = max(m, abs(a[i] - b[i]) / big)
        }
        return m
    }

    private fun doubles(o: JsonObject, key: String) = (o[key] as JsonArray).map { it.jsonPrimitive.doubleOrNull!! }.toDoubleArray()

    /** `block ordinal -> tick -> live display` of one cast, replayed from the dump's spawn / write / remove events. */
    private fun blocks(skill: String): Map<Int, Map<Int, Pair<DoubleArray, DoubleArray>>> {
        val events = VanillaDump.dump(skill).map { Json.parseToJsonElement(it) as JsonObject }.filter { it["e"]?.jsonPrimitive?.contentOrNull in setOf("spawn", "write", "remove") }
        val byTick = events.groupBy { it["t"]!!.jsonPrimitive.intOrNull!! }
        val cur = HashMap<Int, Disp>()
        val kinds = HashMap<Int, String>()
        val snaps = HashMap<Int, Map<Int, Pair<DoubleArray, DoubleArray>>>() // tick -> spawn id -> (effective rotation, scale)
        val spawnOrder = ArrayList<Pair<Pair<Int, Int>, Int>>()
        for (t in 0..120) {
            for (e in byTick[t].orEmpty()) {
                val id = e["id"]!!.jsonPrimitive.intOrNull!!
                when (e["e"]!!.jsonPrimitive.content) {
                    "spawn" -> {
                        cur[id] = Disp(t, id)
                        kinds[id] = e["kind"]!!.jsonPrimitive.content
                        if (kinds[id] == "block") spawnOrder += (t to id) to id
                    }
                    "write" -> cur[id]?.let { d ->
                        d.yaw = e["yaw"]!!.jsonPrimitive.doubleOrNull!!
                        d.pitch = e["pitch"]!!.jsonPrimitive.doubleOrNull!!
                        d.left = doubles(e, "left")
                        d.scale = doubles(e, "scale")
                    }
                    "remove" -> cur[id]?.alive = false
                }
            }
            snaps[t] = cur.filter { (id, d) -> d.alive && kinds[id] == "block" }.mapValues { (_, d) -> effective(d) to d.scale.copyOf() }
        }
        val ordinal = spawnOrder.sortedWith(compareBy({ it.first.first }, { it.first.second })).mapIndexed { n, (_, id) -> id to n }.toMap()
        val out = HashMap<Int, MutableMap<Int, Pair<DoubleArray, DoubleArray>>>()
        for ((t, snap) in snaps) for ((id, v) in snap) out.getOrPut(ordinal.getValue(id)) { HashMap() }[t] = v
        return out
    }

    @Test
    fun `orient and scale of shipped vfx displays match the v1 numbers tick by tick`() {
        val rows = FrameOrientScaleV1ParityScenarioTest::class.java.getResource("/vanilla-parity/frame-orient-v1.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertThat(rows).describedAs("the v1 reference rows").hasSizeGreaterThan(300)
        val problems = ArrayList<String>()
        for ((skill, skillRows) in rows.groupBy { it[0] }) {
            val actual = blocks(skill)
            for (r in skillRows) {
                val ordinal = r[1].toInt()
                val tick = r[2].toInt()
                val wantRot = DoubleArray(4) { r[3 + it].toDouble() }
                val wantScale = DoubleArray(3) { r[7 + it].toDouble() }
                val got = actual[ordinal]?.get(tick)
                if (got == null) { problems += "$skill block#$ordinal t$tick: not alive"; continue }
                val a = angleDeg(wantRot, got.first)
                val s = scaleDiff(wantScale, got.second)
                if (a > 3.0) problems += "$skill block#$ordinal t$tick: rotation off by %.1f deg".format(a)
                if (s > 0.05) problems += "$skill block#$ordinal t$tick: scale %s vs v1 %s".format(got.second.toList(), wantScale.toList())
            }
        }
        assertThat(problems).describedAs("displays that differ from v1").isEmpty()
    }
}
