package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0303 W2-08 (R19 R23 R27 R29 R30 R33): display `flipbook` / `color` / `see_through`, keys `alpha` / `color`, `frame` `axes` and
 * `caster.look`, `rig` `of` / `reset`. Needs the Wave 1b document decoder + compiler and a `SpriteCatalog` with `frames()` (sprite
 * `arcane-slash`, 6 frames) in the harness.
 */
class DisplayOpScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: display-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 100", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compiled(vararg ops: String): CompiledSkill =
        when (val r = loadInline(skill(*ops))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    @Test
    fun rigResetIsZeroDurationWrite() {
        val s = compiled(
            "{id: r, op: rig, parent: caster, for: 80, swing: [" +
                "{at: 4, for: 6, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading}, {at: 67, reset: true}]}",
        )
        assertThat(rows(s).any { it.action is Action.Effect }).isTrue()
    }

    @Test
    fun alignAcceptsNamedDirectionAndUp() {
        // `out` (0301 short name) is `out_quad` outside a keys segment (ADR-0303 3.7). R42: `to` may be a named direction and `up` a named direction; the lowering writes the live rotation with both
        val s = compiled(
            "{id: pose, op: frame, anchor: caster, for: 34, motion: [{law: align, axis: forward, to: world.up, up: caster.forward, step: 1, at: 0, for: 6, ease: out_quad}]}",
        )
        val live = rows(s).mapNotNull { (it.action as? Action.Key)?.leftRotation as? Val.Live }
        assertThat(live).isNotEmpty()
        assertThat(live.first().name).contains("world.up").contains("caster.forward")
        // velocity form: `up` other than world.up is a load error
        val bad = loadInline(
            skill("{id: p, op: frame, anchor: caster, for: 20, motion: [{law: move, velocity: {forward: 1}, at: 0, for: 10}, {law: align, axis: forward, to: velocity, up: caster.up, at: 0, for: 10}]}"),
        )
        assertThat(bad).isInstanceOf(Result.Failure::class.java)
    }

}
