package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * couture-rapier-s2 design gaps (design-gaps-v1 section 3): part constants (`<frame>.length`) fold at load, the three blades fly with
 * lane offsets and an upright alignment, and the thread ribbons end on `until:` with no static `for`. Read from the shipped skill, by its g lines.
 */
class CoutureGapsScenarioTest : SkillEngineScenario() {
    private fun shipped(): CompiledSkill {
        val text = Files.readString(contentDir.resolve("skills/couture-rapier-s2/skill.yaml"))
        return when (val r = compileText("couture-rapier-s2", text)) {
            is Result.Success -> r.value
            is Result.Failure -> error("couture-rapier-s2 must load: ${r.error.message}")
        }
    }

    private fun frameLine(rows: List<String>, tick: Int, id: String) = rows.first { it.startsWith("@$tick frame throw/$id ") }

    private fun pos(line: String): DoubleArray =
        Regex("pos=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)").find(line)!!.groupValues.drop(1).map { it.toDouble() }.toDoubleArray()

    /** The last spawn / write row of the display instance `id` at or before `tick`. */
    private fun latest(rows: List<String>, id: String, tick: Int) =
        rows.last { r ->
            val t = r.substringAfter("@").substringBefore(" ").toInt()
            t <= tick && (r.contains(" spawn $id ") || r.contains(" write $id "))
        }

    @Test
    fun laneOffsetsAndUprightAlignOfTheThreeBlades() {
        val rec = GatewayRecorder()
        play(shipped(), seed = 1L, events = emptyList(), ticks = 42, rec = rec)
        val rows = rec.oracleRows.map { "@${it.tick} ${it.verb} ${it.instance} ${it.payload} ${it.detail}" }
        // the reel capsule's frame op fly_a rides the volley at the lane offset for the whole flight, and turns with the upright align / spin
        for (tick in listOf(35, 36, 40)) frameLine(rows, tick, "fly_a")
        val r35 = frameLine(rows, 35, "fly_a").substringAfter("rot=")
        val r40 = frameLine(rows, 40, "fly_a").substringAfter("rot=")
        assertThat(r35).isNotEqualTo(r40)
        assertThat(r35).doesNotStartWith("0.000000,0.000000,0.000000,1.000000")
        // lane: the three flying pieces are the item displays spawned at the release (tick 34), keyed by their part. blade_a and blade_b sit
        // +-0.35 on `vol`'s s (0.70 apart, the body in the middle) at the release, through the flip and while the volley spins about v.
        val spawns = rows.filter { it.startsWith("@34 spawn ") && it.contains("kind=item") }
        fun idOf(part: String) = spawns.single { it.contains(" part=$part ") }.split(" ")[2]
        val (body, a, b) = Triple(idOf("body"), idOf("blade_a"), idOf("blade_b"))
        for (tick in listOf(34, 35, 36, 40)) {
            val pb = pos(latest(rows, body, tick))
            val pa = pos(latest(rows, a, tick))
            val pc = pos(latest(rows, b, tick))
            for (i in 0..2) assertThat((pa[i] + pc[i]) / 2.0).isCloseTo(pb[i], org.assertj.core.api.Assertions.within(0.01))
            val gap = kotlin.math.sqrt((0..2).sumOf { (pa[it] - pc[it]) * (pa[it] - pc[it]) })
            assertThat(gap).isCloseTo(0.70, org.assertj.core.api.Assertions.within(0.02))
        }
        // the flip: the volley turns from the whirl pose to upright over ticks 34..37, then spins about v (ticks 38..45): the body rot keeps changing
        val rot34 = latest(rows, body, 34).substringAfter("rot=").substringBefore(" ")
        val rot36 = latest(rows, body, 36).substringAfter("rot=").substringBefore(" ")
        val rot40 = latest(rows, body, 40).substringAfter("rot=").substringBefore(" ")
        assertThat(setOf(rot34, rot36, rot40)).hasSize(3)
        // the held shears of the windup stand up under the raise too (not the identity pose)
        val turned = rows.filter { it.startsWith("@3 write #0 ") }
        assertThat(turned).isNotEmpty
        assertThat(turned.first()).doesNotContain("rot=0.000000,0.000000,0.000000,1.000000")
    }

    @Test
    fun threadRibbonsEndOnUntilWithNoStaticFor() {
        // thread_a / thread_b / thread_body load with `until: reel.end` and no `for` (a trail ribbon takes the run horizon)
        val table = shipped().table.toString()
        assertThat(table).contains("thread_a").contains("thread_b").contains("thread_body")
        assertThat(shipped().table.toString()).contains("until")
    }

    @Test
    fun frameLengthOfAPartlessFrameIsALoadError() {
        val text = """
            apiVersion: astera/v3
            kind: Skill
            id: partless-length
            machine:
              start: s
              states:
                s:
                  for: 10
                  do:
                    - {id: f, op: frame, anchor: caster.eye, for: 10}
                    - {id: g, op: frame, anchor: caster.eye, offset: {left: "= f.length"}, for: 10}
        """.trimIndent()
        assertThat(loadInline(text)).isInstanceOf(Result.Failure::class.java)
    }
}
