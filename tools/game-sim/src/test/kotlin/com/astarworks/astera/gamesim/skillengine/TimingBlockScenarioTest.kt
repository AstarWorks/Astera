package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A3 (ADR-0303 12): the shared timing block and timing groups, asserted on the compiled table. Owned by W1-01; every case compiles
 * an inline skill through the real decoder and compiler and reads the `cast` state's rows (runs at the wave 1b gate).
 */
class TimingBlockScenarioTest : SkillEngineScenario() {

    private fun snd(extra: String, id: String = "tick"): String =
        "{id: $id, op: sound, sound: block.note_block.hat, anchor: muzzle, volume: 1.0, pitch: 1.0, $extra}"

    private fun skill(body: String, forTicks: Int = 40): String = """
apiVersion: astera/v3
kind: Skill
id: timing-case
item: any
meta: {cooldown_ticks: 0, resource_cost: 0}
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

    private fun compile(body: String, forTicks: Int = 40): CompiledSkill =
        when (val r = loadInline(skill(body, forTicks))) {
            is Result.Success -> r.value
            is Result.Failure -> error("expected the skill to load, got ${r.error}")
        }

    private fun castRows(s: CompiledSkill): List<Row> = s.table.states.first { it.state.value == "cast" }.rows

    private fun sounds(s: CompiledSkill): List<Row> = castRows(s).filter { it.action is Action.Sound }

    private fun ticks(rows: List<Row>): List<Int> = rows.map { it.tick }

    // Instance ids are state-qualified (ADR-0303 C2: an entry is (state, tick, op id, occurrence i, action); section 9: `/x` is absolute from the state root),
    // so every id the `cast` state emits reads `cast/<path>`.
    private fun ids(rows: List<Row>): List<String> = rows.map { it.instance.text }

    @Test
    fun atForEvery() {
        val s = compile("        - ${snd("at: 4, every: 4, for: 12")}")
        assertThat(ticks(sounds(s))).containsExactly(4, 8, 12)
        assertThat(ids(sounds(s))).containsExactly("cast/tick[0]", "cast/tick[1]", "cast/tick[2]")
    }

    @Test
    fun timesCapsOccurrences() {
        assertThat(ticks(sounds(compile("        - ${snd("at: 0, every: 3, times: 2")}")))).containsExactly(0, 3)
        // v3 (ADR-0303 section 12 / loader message "every: write for or times, not both"): `for` + `times` together is a load error,
        // where v2 let the smaller of the two win; the window-only form is ceilCountOnNondivisibleWindow
        val both = loadInline(skill("        - ${snd("at: 0, every: 3, times: 10, for: 7")}", 40))
        assertThat((both as Result.Failure).error.toString()).contains("write for or times, not both")
    }

    @Test
    fun ceilCountOnNondivisibleWindow() {
        assertThat(ticks(sounds(compile("        - ${snd("at: 0, every: 3, for: 7")}")))).containsExactly(0, 3, 6)
    }

    @Test
    fun untilEventEndsWindowAndInstances() {
        val s = compile("        - ${snd("at: 4, every: 4, until: input.use")}", forTicks = 20)
        // `until` is a mask over the state horizon: every candidate through tick 19 is a row, each marked with the event
        assertThat(ticks(sounds(s))).containsExactly(4, 8, 12, 16)
        assertThat(sounds(s).map { it.maskUntil.map { e -> e.value } }).allMatch { it == listOf("input.use") }
    }

    @Test
    fun untilMasksCandidatesThroughHorizon() {
        val s = compile("        - ${snd("at: 0, every: 5, until: input.use")}", forTicks = 12)
        assertThat(ticks(sounds(s))).containsExactly(0, 5, 10)
    }

    @Test
    fun lastsEndsEachOccurrence() {
        val s = compile(
            """
        - id: pulse
          at: 0
          every: 10
          for: 30
          lasts: 6
          do:
            - {id: core, op: display, kind: item, item: fire_charge, context: fixed, anchor: muzzle, scale: [0.6, 0.6, 0.6], at: 0, for: cast}
""".trimEnd(),
        )
        val ends = castRows(s).filter { it.action is Action.End }
        assertThat(ids(ends)).containsExactly("cast/pulse[0]/core", "cast/pulse[1]/core", "cast/pulse[2]/core")
        assertThat(ticks(ends)).containsExactly(6, 16, 26)
    }

    @Test
    fun nestedGroupsIndexAndParentIndex() {
        val s = compile(
            """
        - id: outer
          at: 0
          every: 10
          for: 20
          do:
            - id: inner
              at: 0
              every: 2
              for: 6
              do:
                - ${snd("at: 0", id = "puff")}
""".trimEnd(),
        )
        assertThat(ids(sounds(s))).containsExactly(
            "cast/outer[0]/inner[0]/puff", "cast/outer[0]/inner[1]/puff", "cast/outer[0]/inner[2]/puff",
            "cast/outer[1]/inner[0]/puff", "cast/outer[1]/inner[1]/puff", "cast/outer[1]/inner[2]/puff",
        )
        assertThat(ticks(sounds(s))).containsExactly(0, 2, 4, 10, 12, 14)
    }

    @Test
    fun occurrenceClockIsLocal() {
        val s = compile(
            """
        - id: g
          at: 5
          every: 10
          for: 20
          do:
            - ${snd("at: 3", id = "hit")}
""".trimEnd(),
        )
        // occurrences at 5 and 15; the child's `at: 3` is relative to each occurrence
        assertThat(ticks(sounds(s))).containsExactly(8, 18)
    }

    @Test
    fun familyTimesCreatesIndexedInstances() {
        val s = compile("        - ${snd("at: 2, times: 3", id = "blade")}")
        assertThat(ids(sounds(s))).containsExactly("cast/blade[0]", "cast/blade[1]", "cast/blade[2]")
        assertThat(ticks(sounds(s))).containsExactly(2, 2, 2)
    }

    @Test
    fun repeatingFamilyIsGroupPlusFamily() {
        val s = compile(
            """
        - id: pulse
          at: 0
          every: 4
          times: 2
          do:
            - ${snd("at: 0, times: 3", id = "blade")}
""".trimEnd(),
        )
        assertThat(ids(sounds(s))).containsExactly(
            "cast/pulse[0]/blade[0]", "cast/pulse[0]/blade[1]", "cast/pulse[0]/blade[2]",
            "cast/pulse[1]/blade[0]", "cast/pulse[1]/blade[1]", "cast/pulse[1]/blade[2]",
        )
        assertThat(ticks(sounds(s))).containsExactly(0, 0, 0, 4, 4, 4)
    }
}
