package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Content-addressed row seed (ADR-0303 rev4.8, design (h)): `rand()` and the particle spread of a row derive from the row's own content,
 * never from an op id or its place in the document. Renaming or reordering ops keeps every draw; byte-identical rows still differ; a random
 * value can never reach a schedule.
 */
class RowSeedScenarioTest : SkillEngineScenario() {
    private fun skill(vararg ops: String): String = """
        apiVersion: astera/v3
        kind: Skill
        id: row-seed-probe
        anchors:
          - {id: body, anchor: caster, mode: follow}
        machine:
          start: s
          states:
            s:
              for: 20
              do:
        """.trimIndent() + "\n" + ops.joinToString("\n") { "                - $it" }

    private fun loaded(yaml: String): CompiledSkill {
        val r = loadInline(yaml)
        assertTrue(r is Result.Success, "expected a load, got $r")
        return (r as Result.Success).value
    }

    private fun spread(id: String, particle: String, at: Int) =
        "{id: $id, op: particle, particle: $particle, anchor: body, at: $at, times: 3, shift: {left: \"= rand() * 4.0\", up: \"= i\"}}"

    /** The recorded particle calls without the order they were authored in: (tick, payload incl. position, offset and seed). */
    private fun bursts(s: CompiledSkill, seed: Long): List<String> =
        play(s, seed).filter { it.verb == "particle" }.map { "${it.tick} ${it.payload} ${it.detail}" }.sorted()

    @Test
    fun renameAndReorderKeepEverySpread() {
        val a = loaded(skill(spread("flame_ring", "flame", 2), spread("smoke_ring", "smoke", 5)))
        val b = loaded(skill(spread("display_9", "smoke", 5), spread("x", "flame", 2)))
        val before = bursts(a, 7L)
        assertEquals(6, before.size)
        assertEquals(before, bursts(b, 7L), "renamed and reordered ops must draw the same spread")
        assertNotEquals(before, bursts(a, 8L), "a different cast seed must still re-roll")
        assertEquals(before.toSet().size, before.size, "the three copies of a times family draw differently")
    }

    @Test
    fun changingWhatARowIsRerollsOnlyThatRow() {
        val a = bursts(loaded(skill(spread("p", "flame", 2), spread("q", "smoke", 5))), 7L)
        val b = bursts(loaded(skill(spread("p", "flame", 3), spread("q", "smoke", 5))), 7L)
        assertEquals(3, a.filter { it.startsWith("5 ") }.size)
        assertEquals(a.filter { it.startsWith("5 ") }, b.filter { it.startsWith("5 ") }, "the untouched row keeps its draw")
    }

    @Test
    fun identicalRowsStillDiffer() {
        val one = "{id: a, op: particle, particle: flame, anchor: body, at: 2, shift: {left: \"= rand() * 4.0\"}}"
        val two = "{id: b, op: particle, particle: flame, anchor: body, at: 2, shift: {left: \"= rand() * 4.0\"}}"
        val s = loaded(skill(one, two))
        val seeds = s.table.states.flatMap { it.rows }.filter { it.action is com.astarworks.astera.core.domain.skillengine.table.Action.Particle }.map { it.seed }
        assertEquals(2, seeds.size)
        assertNotEquals(seeds[0], seeds[1], "a deliberately doubled emitter keeps two streams")
        val calls = bursts(s, 7L)
        assertEquals(2, calls.size)
        assertNotEquals(calls[0], calls[1])
        // the document order only matters between true duplicates: swapping the ids changes nothing
        assertEquals(calls, bursts(loaded(skill(two.replace("id: b", "id: z"), one.replace("id: a", "id: y"))), 7L))
    }

    private fun assertRuntimeTiming(yaml: String, field: String) {
        when (val r = loadInline(yaml)) {
            is Result.Success -> throw AssertionError("rand in '$field' loaded; it must be a named load error")
            is Result.Failure -> {
                assertTrue(r.error is SkillDecodeError.RuntimeTiming, "$field: expected RuntimeTiming, got ${r.error::class.simpleName}: ${r.error}")
                assertTrue(r.error.message.contains(field), "$field: the error must name the field: ${r.error.message}")
            }
        }
    }

    @Test
    fun randInATimingCellIsANamedLoadError() {
        fun op(timing: String) = skill("{id: p, op: particle, particle: flame, anchor: body, $timing}")
        assertRuntimeTiming(op("at: \"= rand() * 10.0\""), "at")
        assertRuntimeTiming(op("for: \"= rand() * 10.0\""), "for")
        assertRuntimeTiming(op("every: \"= rand() * 10.0\", times: 3"), "every")
        assertRuntimeTiming(op("every: 2, times: \"= roll('n') * 5.0\""), "times")
    }

    @Test
    fun randInAStateTimerIsANamedLoadError() {
        val head = """
            apiVersion: astera/v3
            kind: Skill
            id: row-seed-timer
            anchors:
              - {id: body, anchor: caster, mode: follow}
            machine:
              start: s
              states:
        """.trimIndent() + "\n"
        assertRuntimeTiming(head + "    s: {for: \"= rand() * 10.0\", then: e}\n    e: {final: true}\n", "for")
        assertRuntimeTiming(head + "    s: {on: [{after: \"= rand() * 10.0\", to: e}]}\n    e: {final: true}\n", "after")
    }
}
