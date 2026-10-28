package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * SKL-14: every display a shipped skill spawns is a rotation times a scale, never a shear or a runaway stretch.
 * Casts every shipped skill, records the matrices the Paper gateway would send ([RecordingVisuals]) and checks, for mesh /
 * plate / line, that the three axes are orthogonal, the rotation is a unit quaternion and no axis exceeds the size bound.
 * Trail ribbons are sheared parallelograms by design and are exempt. A plate's width and height are independent
 * (a shield rim authored 0.58 x 0.3 stays an oval).
 */
class VfxNoStretchScenarioTest {
    private companion object {
        const val MAX_PLATE_BLOCKS = 12.0
        const val MAX_PLATE_TICKS = 60
        const val MAX_FLASH_TICKS = 6
    }

    private val origin = Vec3(37.5, -60.0, -12.5)

    @Test
    fun `every shipped display keeps orthogonal axes and its authored size`() {
        val dummies = (1..4).associate { simVictim(it) to ahead(origin, 30.0, 2.0 * it) }
        val ids = CastSim(dummies, origin = origin, yawDeg = 30.0).catalog.ids().filter { '@' !in it }.sorted()
        val bad = ArrayList<String>()
        var checked = 0
        var nonSquarePlate = false
        for (id in ids) {
            lateinit var sim: CastSim
            val rec = RecordingVisuals(now = { sim.now })
            sim = CastSim(dummies, origin = origin, yawDeg = 30.0, visualGateway = rec, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5))
            runCatching { sim.cast(id, 120) }
            // lifetime as seen in the client: a plate left alive for the whole cast is a streak that stays on screen
            for (l in rec.lives.values) {
                if (l.kind != "plate") continue
                // only long thin plates (a streak) must not linger; stance rims and orbs may live as long as their skill
                val dims = rec.frames.filter { it.handle == l.handle && it.kind == "plate" }
                if (dims.none { minOf(it.renderedW, it.renderedH) < 0.4 && maxOf(it.renderedW, it.renderedH) > 3.0 }) continue
                val ended = l.removeTick ?: 120
                if (ended - l.spawnTick > MAX_PLATE_TICKS) bad += "$id plate ${l.note} spawned t${l.spawnTick} lives ${ended - l.spawnTick} ticks (cap $MAX_PLATE_TICKS, removed=${l.removeTick})"
            }
            for (f in rec.frames) {
                if (f.kind == "ribbon") continue
                checked++
                val len = f.cols.map { it.length() }
                for ((a, b) in listOf(0 to 1, 0 to 2, 1 to 2)) {
                    val cos = abs(f.cols[a].dot(f.cols[b])) / (len[a] * len[b] + 1e-12)
                    if (cos > 1e-6) bad += "$id t${f.tick} ${f.kind} axes $a/$b not orthogonal (cos=$cos)"
                }
                if (abs(f.rotationNormSq - 1.0) > 1e-9) bad += "$id t${f.tick} ${f.kind} rotation not unit (${f.rotationNormSq})"
                // display units are the vanilla scale field: meshes stay under 4, lines under 20 blocks long (8 units per block)
                val capX = if (f.kind == "line") 8.0 * 20.0 else if (f.kind == "plate") Double.MAX_VALUE else 4.0
                val capY = if (f.kind == "line") 8.0 * 20.0 else if (f.kind == "plate") Double.MAX_VALUE else 4.0
                if (len[0] > capX || len[1] > capY) bad += "$id t${f.tick} ${f.kind} stretched: axis lengths ${len.map { "%.2f".format(it) }} (authored ${f.authored})"
                // plates are judged as the client draws them: display scale x sprite px x 0.025 blocks
                // (a screen-wide flash is allowed only as a flash: v1 dash smear scale 26..40 lived 3 ticks)
                val life = rec.lives[f.handle]?.let { (it.removeTick ?: 120) - it.spawnTick } ?: 0
                if (f.kind == "plate" && maxOf(f.renderedW, f.renderedH) > MAX_PLATE_BLOCKS && life > MAX_FLASH_TICKS) {
                    bad += "$id t${f.tick} plate ${f.note} draws ${"%.1f".format(f.renderedW)} x ${"%.1f".format(f.renderedH)} blocks (cap $MAX_PLATE_BLOCKS unless it lives <= $MAX_FLASH_TICKS ticks; lives $life)"
                }
                if (f.kind == "plate" && abs(len[0] - len[1]) > 1e-6) nonSquarePlate = true
            }
        }
        assertTrue(checked > 1000, "recorded only $checked display frames; the recording gateway is not wired")
        assertTrue(nonSquarePlate, "no plate with independent width/height was recorded; per-axis track scale regressed")
        assertTrue(bad.isEmpty(), "stretched / sheared displays:\n" + bad.take(30).joinToString("\n"))
    }
}
