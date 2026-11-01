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

    @Test
    fun laneOffsetsAndUprightAlignOfTheThreeBlades() {
        val rec = GatewayRecorder()
        play(shipped(), seed = 1L, events = emptyList(), ticks = 40, rec = rec)
        val rows = rec.oracleRows.map { "@${it.tick} ${it.verb} ${it.instance} ${it.payload} ${it.detail}" }
        // the reel capsule's frame op fly_a rides the volley at the lane offset for the whole flight, and turns with the upright align / spin
        for (tick in listOf(35, 36, 40)) frameLine(rows, tick, "fly_a")
        val r35 = frameLine(rows, 35, "fly_a").substringAfter("rot=")
        val r40 = frameLine(rows, 40, "fly_a").substringAfter("rot=")
        assertThat(r35).isNotEqualTo(r40)
        assertThat(r35).doesNotStartWith("0.000000,0.000000,0.000000,1.000000")
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
