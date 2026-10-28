package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Compose rules of ADR-0303 (W1b-02b): item 1 (`every` with both `for` and `times`), `${p}` binding, check 5 (declared `emits`),
 * check 6 (`max_part_depth`), unknown CEL functions, and example j's op-level call sub-tables. Every case goes through the real
 * decoder and [com.astarworks.astera.core.application.skillengine.compose.SkillCompiler] (runs at the wave 1b gate).
 */
class ComposeRulesScenarioTest : SkillEngineScenario() {

    private fun skill(body: String, header: String = "", forTicks: Int = 40): String = """
apiVersion: astera/v3
kind: Skill
id: compose-case
item: any
meta: {cooldown_ticks: 0, resource_cost: 0}
$header
anchors:
  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.6}}
machine:
  start: cast
  states:
    cast:
      for: $forTicks
      then: done
      do:
$body
    done: {final: true}
"""

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error, the skill compiled")
        is Result.Failure -> r.error.toString()
    }

    private fun snd(extra: String): String =
        "        - {id: tick, op: sound, sound: block.note_block.hat, anchor: muzzle, volume: 1.0, pitch: 1.0, $extra}"

    @Test
    fun everyWithForAndTimesIsRejected() {
        val msg = failure(skill(snd("at: 0, every: 4, for: 12, times: 2")))
        assertThat(msg).contains("write for or times, not both")
    }

    @Test
    fun everyWithOnlyTimesOrOnlyForIsFine() {
        for (extra in listOf("at: 0, every: 4, times: 2", "at: 0, every: 4, for: 12")) {
            assertThat(loadInline(skill(snd(extra)))).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun paramDefaultIsSubstitutedBeforeLowering() {
        val header = "params:\n  gap: {type: ticks, default: 5}"
        val s = (loadInline(skill(snd("at: 0, every: \${gap}, for: 12"), header)) as Result.Success).value
        val ticks = s.table.states.first { it.state.value == "cast" }.rows.filter { it.action is Action.Sound }.map { it.tick }
        assertThat(ticks).containsExactly(0, 5, 10)
    }

    @Test
    fun declaredEmitNeverEmittedIsCheckFive() {
        val msg = failure(skill(snd("at: 0"), "emits: [ghost]"))
        assertThat(msg).contains("emits 'ghost' is declared but never emitted")
    }

    @Test
    fun declaredEmitThatIsEmittedPasses() {
        val body = "        - {id: e, emit: ping, at: 3}"
        assertThat(loadInline(skill(body, "emits: [ping]"))).isInstanceOf(Result.Success::class.java)
    }

    private fun chain(depth: Int): String {
        val sb = StringBuilder()
        for (k in 0 until depth) {
            val next = if (k + 1 < depth) "      use: p${k + 1}\n      then: end" else "      for: 1\n      then: end"
            sb.append(
                "apiVersion: astera/v3\nkind: Part\nid: p$k\nemits: [end]\nmachine:\n  start: s\n  states:\n    s:\n$next\n    end: {final: true}\n---\n",
            )
        }
        sb.append(skill("        - {id: e, emit: ping, at: 0}").replace("    done: {final: true}", "    done: {final: true}"))
        // the main skill calls p0 from a state
        return sb.toString().replace(
            "    cast:\n      for: 40\n      then: done\n",
            "    cast:\n      use: p0\n      then: done\n",
        ).replace("      do:\n        - {id: e, emit: ping, at: 0}\n", "")
    }

    @Test
    fun partDepthAtTheLimitPassesAndOnePastFails() {
        assertThat(loadInline(chain(6))).isInstanceOf(Result.Success::class.java)
        assertThat(failure(chain(7))).containsIgnoringCase("deeper than")
    }

    @Test
    fun unknownCelFunctionIsALoadError() {
        val msg = failure(skill("        - {id: e, op: sound, sound: block.note_block.hat, anchor: muzzle, volume: \"= nosuchfn(1)\", pitch: 1.0, at: 0}"))
        assertThat(msg).containsIgnoringCase("nosuchfn")
    }

    @Test
    fun exampleJCompilesWithItsOpLevelCallSubTables() {
        val s = loadExample("j-mythic-composition")
        writeEvidence("compose-rules-j", s.table)
        val ids = s.table.subTables.values.flatMap { it.rows }.map { it.instance.text }
        assertThat(ids.any { it.contains("splash") }).isTrue()
    }
}
