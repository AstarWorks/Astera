package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * ADR-0303 3.4 / 3.5 motion-law grammar through the real loader and compiler: `= <CEL>` with the copy index `i` in a law number (baked per
 * copy), a named error for a runtime-only value, `grow prop: offset` + `accel`, `[left, up, forward]` triples in `keys.scale` / `grow.to`,
 * and a CEL string inside a family `motion` (which used to be misread as a per-index list).
 */
class MotionLawGrammarScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: law-grammar", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 100", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compiled(vararg ops: String): CompiledSkill =
        when (val r = loadInline(skill(*ops))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }

    private fun keyRows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }.filter { it.action is Action.Key }

    private fun key(r: Row) = r.action as Action.Key

    private fun rotDegrees(r: Row): Double {
        val q = (key(r).leftRotation as Val.Known).value
        return Math.toDegrees(2.0 * Math.acos(q.w.coerceIn(-1.0, 1.0)))
    }

    @Test
    fun lawNumberReadsCopyIndexAndBakesPerCopy() {
        // deg_per_tick = 10 + i*2 over 5 ticks: copies end at 50, 60, 70 degrees (two keys each, never cut)
        val s = compiled(
            "{id: orb, op: display, kind: item, item: couture-rapier, anchor: caster, times: 3, " +
                "motion: [{law: spin, axis: up, deg_per_tick: \"= 10 + i * 2\", for: 5}], for: 40}",
        )
        val ends = keyRows(s).groupBy { it.instance }.values.map { rows -> rotDegrees(rows.maxBy { it.tick + key(it).durationTicks }) }
        assertThat(ends).hasSize(3)
        val sorted = ends.sorted()
        listOf(50.0, 60.0, 70.0).forEachIndexed { k, want -> assertThat(sorted[k]).isCloseTo(want, within(1e-3)) }
    }

    @Test
    fun lawNumberOfRuntimeValueIsNamedLoadError() {
        val r = loadInline(
            skill(
                "{id: orb, op: display, kind: item, item: couture-rapier, anchor: caster, times: 3, " +
                    "motion: [{law: spin, axis: up, deg_per_tick: \"= nearest_enemy_dist(caster)\", for: 5}], for: 40}",
            ),
        )
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        val text = (r as Result.Failure).error.toString()
        assertThat(text).contains("nearest_enemy_dist").contains("runtime value")
        assertThat(text).doesNotContain("per-index list")
    }

    @Test
    fun growOffsetWithAccelDecaysTheMove() {
        // offset(tau) = 1.0 * tau - 0.1 * tau^2 / 2 along forward (z): 0.95, 1.8, 2.55, 3.2 at ticks 1..4
        val s = compiled(
            "{id: d, op: display, kind: item, item: couture-rapier, anchor: caster, " +
                "motion: [{law: grow, prop: offset, per_tick: {forward: 1.0}, accel: {forward: -0.1}, for: 4}], for: 20}",
        )
        val rows = keyRows(s)
        assertThat(rows).isNotEmpty()
        for (r in rows) {
            val tau = (r.tick + key(r).durationTicks).coerceAtMost(4).toDouble()
            val z = (key(r).translation as Val.Known).value.z
            assertThat(z).isCloseTo(tau - 0.05 * tau * tau, within(1e-6))
        }
        val last = rows.maxBy { it.tick + key(it).durationTicks }
        assertThat((key(last).translation as Val.Known).value.z).isCloseTo(3.2, within(1e-6))
    }

    @Test
    fun scaleTripleEqualsPerAxisMapInKeysAndGrow() {
        fun finalScale(motion: String): Triple<Double, Double, Double> {
            val s = compiled("{id: d, op: display, kind: item, item: couture-rapier, anchor: caster, motion: $motion, for: 20}")
            val v = (key(keyRows(s).maxBy { it.tick + key(it).durationTicks }).scale as Val.Known).value
            return Triple(v.x, v.y, v.z)
        }
        // [left, up, forward] = x, y, z
        assertThat(finalScale("[{law: grow, start: [1, 1, 1], to: [3, 5, 7], for: 4}]")).isEqualTo(Triple(3.0, 5.0, 7.0))
        assertThat(finalScale("[{law: grow, start: {forward: 1, up: 1, left: 1}, to: {forward: 7, up: 5, left: 3}, for: 4}]")).isEqualTo(Triple(3.0, 5.0, 7.0))
        assertThat(finalScale("[{law: keys, keys: [{t: 0, scale: [1, 1, 1]}, {t: 4, scale: [2, 4, 6]}]}]")).isEqualTo(Triple(2.0, 4.0, 6.0))
        assertThat(loadInline(skill("{id: d, op: display, kind: item, item: couture-rapier, anchor: caster, motion: [{law: grow, start: [1, 1, 1], to: [3, 5], for: 4}], for: 20}")))
            .isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun frameLawReadsCopyIndexAndRiderComposesItsCopy() {
        // a frame family: copy k spins (10 + k*2) deg/tick for 5 ticks; a rider on blade[1] carries copy 1's 60 degrees, not the last copy's 70
        val s = compiled(
            "{id: blade, op: frame, anchor: caster, times: 3, motion: [{law: spin, axis: up, deg_per_tick: \"= 10 + i * 2\", for: 5}], for: 40}",
            "{id: rider, op: frame, anchor: \"blade[1]\", for: 40}",
        )
        val byInstance = keyRows(s).groupBy { it.instance }
        fun end(prefix: String): Double =
            rotDegrees(byInstance.filterKeys { it.toString().contains(prefix) }.values.single().let { rows -> rows.maxBy { it.tick + key(it).durationTicks } })
        val blades = byInstance.filterKeys { it.toString().contains("blade") }.values.map { rows -> rotDegrees(rows.maxBy { it.tick + key(it).durationTicks }) }.sorted()
        listOf(50.0, 60.0, 70.0).forEachIndexed { k, want -> assertThat(blades[k]).isCloseTo(want, within(1e-3)) }
        assertThat(end("rider")).isCloseTo(60.0, within(1e-3))
    }

    @Test
    fun projectileLawReadsCopyIndexAndRiderComposesItsCopy() {
        // body copy k spins (5 + k) deg/tick for 4 ticks; a rider on bolt[1] carries copy 1's 24 degrees, not the last copy's
        val s = compiled(
            "{id: bolt, op: projectile, times: 3, origin: caster, velocity: \"= look(caster) * 1\", for: 10, radius: 0.5, hit: {targets: enemies, once: true, attach: false}, " +
                "block: {mode: despawn}, motion: [{law: spin, axis: up, deg_per_tick: \"= 5 + i\", for: 4}]}",
            "{id: rider, op: frame, anchor: \"bolt[1]\", for: 10}",
        )
        val rider = keyRows(s).filter { it.instance.toString().contains("rider") }
        assertThat(rotDegrees(rider.maxBy { it.tick + key(it).durationTicks })).isCloseTo(24.0, within(1e-3))
    }
}
