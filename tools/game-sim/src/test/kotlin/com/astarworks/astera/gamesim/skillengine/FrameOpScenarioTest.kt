package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
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

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

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

    @Test
    fun itemAxisWithoutPartIsLoadError() {
        val r = loadInline(
            skill("{id: open, op: frame, anchor: shears, for: 40, motion: [{law: keys, keys: [{t: 0, pose: [{axis: edge, deg: 5}]}]}]}"),
        )
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }

    // ---- expressions name frame / projectile ids and their part points (ADR-0303 3.5, 1.1) ----

    private fun sound(id: String, at: Int, volume: String) = "{id: $id, op: sound, sound: block.note_block.hat, anchor: shears, volume: \"$volume\", at: $at}"

    @Test
    fun partPointOnPartlessFrameIsLoadError() {
        val r = loadInline(skill("{id: f, op: frame, anchor: shears, for: 40}", sound("snd", 0, "= speed(f.tip)")))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun unknownFrameIdInExpressionIsLoadError() {
        val r = loadInline(skill("{id: f, op: frame, anchor: shears, for: 40}", sound("snd", 0, "= speed(nosuch)")))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }
}
