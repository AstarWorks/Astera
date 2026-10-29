package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import org.junit.jupiter.api.Test

/**
 * G7: `part_length(anchor)` / `part_point(anchor, "point")` read the shipped content/frames/couture-rapier.yaml at expansion, through the
 * real compiler (the one sim and Paper both run). Expected numbers are copied from that file: blade_a length 1.0077, tip (0.4864, 0.6697, 0.0944).
 */
class PartFunctionsScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String, item: String = "couture-rapier"): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: part-fn-probe", "item: $item",
            "anchors:", "  - {id: shears, anchor: caster.eye, mode: follow}",
            "machine:", "  start: throw", "  states:", "    throw:", "      for: 10", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun spawnPosition(s: CompiledSkill, id: String): Vector3d {
        val row = s.table.states.flatMap { it.rows }.first { r -> r.action is Action.Spawn && r.instance.text.endsWith(id) }
        @Suppress("UNCHECKED_CAST")
        return ((row.action as Action.Spawn).pose.position as Val.Known<Vector3d>).value
    }

    private fun compiled(vararg ops: String): CompiledSkill = when (val r = loadInline(skill(*ops))) {
        is Result.Success -> r.value
        is Result.Failure -> error("load failed: ${r.error.message}")
    }


    private val blade = "{id: blade, op: frame, anchor: shears, part: blade_a, for: 10}"

    @Test
    fun partLengthIsTheFramesFileLength() {
        val s = compiled(blade, "{id: probe, op: frame, anchor: shears, offset: {left: \"= part_length(blade)\"}, for: 10}")
        assertThat(spawnPosition(s, "probe").length()).isCloseTo(1.0077, within(1e-9))
    }

    @Test
    fun partLengthOfAFamilyOpIdMultipliesWithLiterals() {
        val s = compiled(
            "{id: blades, op: frame, times: 2, anchor: shears, part: blade_b, for: 10}",
            "{id: probe, op: frame, anchor: shears, offset: {left: \"= -0.35 * part_length(blades)\"}, for: 10}",
        )
        assertThat(spawnPosition(s, "probe").length()).isCloseTo(0.35 * 1.0077, within(1e-9))
    }

    @Test
    fun partPointIsTheFramesFilePoint() {
        val s = compiled(blade, "{id: probe, op: frame, anchor: shears, offset: {left: \"= dot(part_point(blade, 'tip'), vec(1, 0, 0))\"}, for: 10}")
        assertThat(spawnPosition(s, "probe").length()).isCloseTo(0.4864, within(1e-9))
    }

    @Test
    fun anchorWithoutPartIsNamedLoadError() {
        val r = loadInline(skill("{id: plain, op: frame, anchor: shears, for: 10}", "{id: probe, op: frame, anchor: shears, offset: {left: \"= part_length(plain)\"}, for: 10}"))
        val e = (r as Result.Failure).error
        assertThat(e).isInstanceOf(SkillDecodeError.Invalid::class.java)
        assertThat(e.message).contains("part_length(plain)").contains("not a frame op with a part")
    }

    @Test
    fun unknownPointIsNamedLoadError() {
        val r = loadInline(skill(blade, "{id: probe, op: frame, anchor: shears, offset: {left: \"= dot(part_point(blade, 'nose'), vec(1, 0, 0))\"}, for: 10}"))
        val e = (r as Result.Failure).error
        assertThat(e.message).contains("no point 'nose'").contains("tip")
    }

    @Test
    fun unknownPartIsLoadError() {
        val r = loadInline(skill("{id: blade, op: frame, anchor: shears, part: nosuch, for: 10}"))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun dynamicPointNameIsLoadError() {
        val r = loadInline(skill(blade, "{id: probe, op: frame, anchor: shears, offset: {left: \"= dot(part_point(blade, 'a' + 'b'), vec(1, 0, 0))\"}, for: 10}"))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }
}
