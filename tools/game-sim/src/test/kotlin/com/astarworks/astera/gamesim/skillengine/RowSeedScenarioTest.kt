package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Identity-addressed row seed: `rand()` and the particle spread of a row derive from the row's stable identity (skill id, op id with copy
 * indices, tick, kind, sample), never from the text of its spec. Reordering ops keeps every draw, adding a look field keeps it, renaming an
 * op re-rolls only that op; identical rows still differ; a random value can never reach a schedule.
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
    fun reorderKeepsEverySpreadAndRenameRerollsOnlyThatOp() {
        val a = loaded(skill(spread("flame_ring", "flame", 2), spread("smoke_ring", "smoke", 5)))
        val reordered = loaded(skill(spread("smoke_ring", "smoke", 5), spread("flame_ring", "flame", 2)))
        val renamed = loaded(skill(spread("flame_ring", "flame", 2), spread("smoke_x", "smoke", 5)))
        val before = bursts(a, 7L)
        assertEquals(6, before.size)
        assertEquals(before, bursts(reordered, 7L), "reordered ops must draw the same spread")
        assertNotEquals(before, bursts(a, 8L), "a different cast seed must still re-roll")
        assertEquals(before.toSet().size, before.size, "the three copies of a times family draw differently")
        val after = bursts(renamed, 7L)
        assertEquals(before.filter { it.startsWith("2 ") }, after.filter { it.startsWith("2 ") }, "the untouched op keeps its draw")
        assertNotEquals(before.filter { it.startsWith("5 ") }, after.filter { it.startsWith("5 ") }, "the renamed op re-rolls")
    }

    @Test
    fun addingALookFieldKeepsTheDraws() {
        fun p(extra: String) =
            "{id: p, op: particle, particle: flame, anchor: body, at: 2, times: 3, shift: {left: \"= rand() * 4.0\", up: \"= i\"}$extra}"
        fun positions(s: CompiledSkill) = play(s, 7L).filter { it.verb == "particle" }.map { "${it.tick} ${it.payload.substringBefore(" count")}" }
        assertEquals(positions(loaded(skill(p("")))), positions(loaded(skill(p(", extra: 0.0")))), "a look field must not change the draws")

        fun d(extra: String) = "{id: g, op: display, kind: block, anchor: caster, block: minecraft:white_concrete, for: 20$extra}"
        fun seeds(s: CompiledSkill) = s.table.states.flatMap { it.rows }.map { it.seed }
        val plain = seeds(loaded(skill(d(""))))
        assertEquals(plain, seeds(loaded(skill(d(", glowing: false")))), "glowing: false must not change the row seed")
        assertEquals(plain, seeds(loaded(skill(d(", glowing: true")))), "glowing: true must not change the row seed")
    }

    @Test
    fun identicalRowsStillDiffer() {
        val one = "{id: a, op: particle, particle: flame, anchor: body, at: 2, shift: {left: \"= rand() * 4.0\"}}"
        val two = "{id: b, op: particle, particle: flame, anchor: body, at: 2, shift: {left: \"= rand() * 4.0\"}}"
        val s = loaded(skill(one, two))
        val seeds = s.table.states.flatMap { it.rows }.filter { it.action is com.astarworks.astera.core.domain.skillengine.table.Action.Particle }.map { it.seed }
        assertEquals(2, seeds.size)
        assertNotEquals(seeds[0], seeds[1], "two ops keep two streams")
        val calls = bursts(s, 7L)
        assertEquals(2, calls.size)
        assertNotEquals(calls[0], calls[1])
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
