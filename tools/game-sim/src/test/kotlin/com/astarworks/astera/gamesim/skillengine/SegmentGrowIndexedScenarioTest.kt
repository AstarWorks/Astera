package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SegmentSample
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * ADR-0303 rev4.8: `anchor: {of, to, samples}` (n rows along a segment, dump `sample k/n`), `grow by:` (exclusive with to / per_tick),
 * and R28 per-index string lists (`block: [a, b, c]`) through the real loader and compiler.
 */
class SegmentGrowIndexedScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: seg-grow", "item: couture-rapier",
            "anchors:", "  - {id: body, anchor: caster}", "  - {id: tip, anchor: caster, offset: {forward: 4}}",
            "machine:", "  start: s", "  states:", "    s:", "      for: 100", "      enter:",
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

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    private val origin = object : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? =
            if (frame == "caster") Frame(Vec3(10.0, 0.0, 0.0), Quaternion(0.0, 0.0, 0.0, 1.0)) else null

        override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
    }
    private val spec = ExprEnvSpec(frames = setOf("caster"))
    private val compiler = CelExprCompiler()

    private fun eval(v: Val<*>): Vec3 {
        val src = (v as Val.Live<*>).name
        val c = when (val r = compiler.compile(src, spec)) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("'$src' did not compile: ${r.error}")
        }
        return (c.eval(ExprEnv(emptyMap(), origin, 7L, null, 7L, 0)) as ExprValue.Vec3).value
    }

    @Test
    fun particleSegmentLowersToNSampleRows() {
        val s = compiled("{id: line, op: particle, particle: flame, anchor: {of: body, to: tip, samples: 5}}")
        val rs = rows(s).filter { it.action is Action.Particle }
        assertThat(rs).hasSize(5)
        assertThat(rs.map { it.sample }).containsExactly(
            SegmentSample(0, 5), SegmentSample(1, 5), SegmentSample(2, 5), SegmentSample(3, 5), SegmentSample(4, 5),
        )
        assertThat(rs.map { it.tick }.toSet()).hasSize(1)
        val pts = rs.map { eval((it.action as Action.Particle).position) }
        val dx = pts[1].x - pts[0].x
        val dy = pts[1].y - pts[0].y
        val dz = pts[1].z - pts[0].z
        for (k in 1 until pts.size) {
            assertThat(pts[k].x - pts[k - 1].x).isCloseTo(dx, within(1e-6))
            assertThat(pts[k].y - pts[k - 1].y).isCloseTo(dy, within(1e-6))
            assertThat(pts[k].z - pts[k - 1].z).isCloseTo(dz, within(1e-6))
        }
        val tx = pts.last().x - pts.first().x
        val ty = pts.last().y - pts.first().y
        val tz = pts.last().z - pts.first().z
        assertThat(Math.sqrt(tx * tx + ty * ty + tz * tz)).isCloseTo(4.0, within(1e-6))
    }

    @Test
    fun particleDefSegmentLowersAndKeepsSweepRefused() {
        val s = compiled("{id: line, op: particle_def, def: spark, anchor: {of: body, to: tip, samples: 3}}")
        val rs = rows(s).filter { it.action is Action.ParticleDef }
        assertThat(rs.map { it.sample }).containsExactly(SegmentSample(0, 3), SegmentSample(1, 3), SegmentSample(2, 3))
        failure("{id: line, op: particle_def, def: spark, anchor: {of: body, sweep: true}}")
    }

    @Test
    fun segmentLintsAreNamedLoadErrors() {
        failure("{id: a, op: particle, particle: flame, anchor: {of: body, to: tip}}")
        failure("{id: b, op: particle, particle: flame, anchor: {of: body, samples: 4}}")
        failure("{id: c, op: particle, particle: flame, anchor: {of: body, to: tip, samples: 1}}")
        failure("{id: d, op: particle, particle: flame, anchor: {of: body, to: tip, samples: 2.5}}")
    }

    @Test
    fun growByMultipliesTheHeldScale() {
        // grow to 0.5 over 4 ticks (held), then by 0.6 over 4 ticks: the composite ends at 0.5 * 0.6 = 0.3
        val s = compiled(
            "{id: d, op: display, kind: item, item: couture-rapier, anchor: caster, " +
                "motion: [{law: grow, prop: scale, to: 0.5, for: 4}, {law: grow, prop: scale, by: 0.6, at: 4, for: 4}], for: 20}",
        )
        val keys = rows(s).filter { it.action is Action.Key }
        val last = keys.maxBy { it.tick + (it.action as Action.Key).durationTicks }
        val sc = ((last.action as Action.Key).scale as Val.Known).value
        assertThat(sc.x).isCloseTo(0.3, within(1e-6))
        assertThat(sc.y).isCloseTo(0.3, within(1e-6))
        assertThat(sc.z).isCloseTo(0.3, within(1e-6))
    }

    @Test
    fun growByIsExclusiveWithToAndPerTick() {
        listOf("by: 2, to: 3", "by: 2, per_tick: 0.1").forEach {
            failure("{id: d, op: display, kind: item, item: couture-rapier, anchor: caster, motion: [{law: grow, prop: scale, $it, for: 4}], for: 20}")
        }
    }

    @Test
    fun perIndexBlockListBakesOneBlockPerCopy() {
        val s = compiled(
            "{id: tiles, op: display, kind: block, block: [gray_concrete, iron_block, black_concrete], anchor: caster, times: 3, for: 20}",
        )
        val spawns = rows(s).filter { it.action is Action.Spawn }.sortedBy { it.instance.toString() }
        val blocks = spawns.map { ((it.action as Action.Spawn).look.fields["block"] as Val.Known<*>).value }
        assertThat(blocks).containsExactly("gray_concrete", "iron_block", "black_concrete")
    }

    @Test
    fun perIndexBlockListWrongLengthIsLoadError() {
        failure("{id: tiles, op: display, kind: block, block: [gray_concrete, iron_block], anchor: caster, times: 3, for: 20}")
    }
}
