package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 1.1 `frame` op (W1-07). Runs at the wave 1b gate (needs the document decoder, unroller and compiler). */
class FrameOpScenarioTest : SkillEngineScenario() {

    // Instance ids are state-qualified (ADR-0303 C2; section 9): the `throw` state emits `throw/<path>`.

    /** [ops] are flow-style op lines; they become the `enter:` list of the single state. */
    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: frame-probe", "item: couture-rapier",
            "anchors:", "  - {id: shears, anchor: caster.hand_r}",
            "machine:", "  start: throw", "  states:", "    throw:", "      for: 40", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compiled(vararg ops: String): CompiledSkill =
        (loadInline(skill(*ops)) as Result.Success).value

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    /** Frame half of TimingBlockScenarioTest#familyTimesCreatesIndexedInstances: `times: 3` is three instances, indexed, with their own `i`. */
    @Test
    fun familyTimesCreatesIndexedInstances() {
        val s = compiled(
            "{id: blade, op: frame, times: 3, anchor: shears, turn: [{axis: up, deg: \"= i * 120\"}], for: 40}",
        )
        val spawns = rows(s).filter { (it.action as? Action.Spawn)?.kind == SpawnKind.FRAME }
        assertThat(spawns.map { it.instance.text }).containsExactly("throw/blade[0]", "throw/blade[1]", "throw/blade[2]")
        assertThat(spawns.map { it.tick }.toSet()).containsExactly(0)
        assertThat(spawns.map { (it.action as Action.Spawn).pose.rotation }.toSet()).hasSize(3)
        val ends = rows(s).filter { it.action is Action.End }
        assertThat(ends.map { it.instance.text to it.tick }).containsExactlyInAnyOrder("throw/blade[0]" to 40, "throw/blade[1]" to 40, "throw/blade[2]" to 40)
    }

    @Test
    fun frameAnchoredToFamilyMemberBindsSameIndex() {
        val s = compiled(
            "{id: blade, op: frame, times: 3, anchor: shears, for: 40}",
            "{id: tip, op: frame, times: 3, anchor: \"blade[i]\", offset: {forward: 1.0}, for: 40}",
        )
        val tips = rows(s).filter { (it.action as? Action.Spawn)?.kind == SpawnKind.FRAME && it.instance.text.startsWith("throw/tip") }
        assertThat(tips.map { it.instance.text to (it.action as Action.Spawn).anchor?.text })
            .containsExactly("throw/tip[0]" to "throw/blade[0]", "throw/tip[1]" to "throw/blade[1]", "throw/tip[2]" to "throw/blade[2]")
    }

    @Test
    fun bareFamilyNameAsParentIsLoadError() {
        val r = loadInline(
            skill(
                "{id: blade, op: frame, times: 3, anchor: shears, for: 40}",
                "{id: tip, op: frame, anchor: blade, for: 40}",
            ),
        )
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }

    @Test
    fun indexOutOfRangeIsLoadError() {
        val r = loadInline(
            skill(
                "{id: blade, op: frame, times: 3, anchor: shears, for: 40}",
                "{id: tip, op: frame, anchor: \"blade[3]\", for: 40}",
            ),
        )
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
    }
}
