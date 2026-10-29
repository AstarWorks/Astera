package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Per-copy `at:` in a `times` family (no `every`): a list of exactly n ticks or a `= CEL` over `i`, folded at load, so each copy starts on its own
 * whole tick (the bastion ring quarters). A negative, non-folding, wrong-length or past-the-state-end start is a named load error.
 */
class TimesAtScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: times-at", "item: couture-rapier",
            "anchors:", "  - {id: body, anchor: caster, mode: follow}",
            "machine:", "  start: s", "  states:", "    s:", "      for: 20", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compiled(vararg ops: String): CompiledSkill =
        when (val r = loadInline(skill(*ops))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }

    private fun failure(vararg ops: String): String =
        when (val r = loadInline(skill(*ops))) {
            is Result.Success -> throw AssertionError("expected a load error")
            is Result.Failure -> r.error.toString()
        }

    private fun spawnTicks(s: CompiledSkill): Map<String, Int> =
        s.table.states.flatMap { it.rows }.filter { it.action is Action.Spawn }.associate { r: Row -> "${r.instance.path.last().op.value.substringAfterLast('/')}[${r.instance.path.last().index}]" to r.tick }

    @Test
    fun listAtGivesEachCopyItsOwnTick() {
        val s = compiled("{id: q, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: body, times: 4, at: [0, 1, 4, 5], for: 6}")
        assertThat(spawnTicks(s)).containsEntry("q[0]", 0).containsEntry("q[1]", 1).containsEntry("q[2]", 4).containsEntry("q[3]", 5).hasSize(4)
    }

    @Test
    fun celAtReadsTheCopyIndexAndForCanEndAllCopiesTogether() {
        val s = compiled("{id: q, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: body, times: 3, at: \"= 2 * int(i) + 1\", for: \"= 10 - (2 * int(i) + 1)\"}")
        assertThat(spawnTicks(s)).containsEntry("q[0]", 1).containsEntry("q[1]", 3).containsEntry("q[2]", 5)
        val ends = s.table.states.flatMap { it.rows }.filter { it.action is Action.End }.map { it.tick }.distinct()
        assertThat(ends).containsExactly(10)
    }

    @Test
    fun badPerCopyAtIsANamedLoadError() {
        assertThat(failure("{id: q, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: body, times: 3, at: [0, -1, 2], for: 4}")).contains("copy 1")
        assertThat(failure("{id: q, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: body, times: 3, at: \"= rand() * 4\", for: 4}")).contains("at")
        assertThat(failure("{id: q, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: body, times: 3, at: [0, 1], for: 4}")).contains("3")
        assertThat(failure("{id: q, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: body, times: 3, at: [0, 1, 25], for: 4}")).contains("copy 2")
        assertThat(failure("{id: q, op: display, kind: sprite, sprite: bastion-arc, size: [1.0, 1.0], anchor: body, times: 3, at: \"= 0.5 * i\", for: 4}")).contains("copy")
    }
}
