package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/** ADR-0303 3.4: particles and sounds take `motion:` property laws, evaluated once per occurrence at `tau = i * every`. */
class PointLawsScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (listOf(
        "apiVersion: astera/v3", "kind: Skill", "id: point-laws-probe", "item: couture-rapier",
        "machine:", "  start: s", "  states:", "    s:", "      for: 10", "      do:",
    ) + ops.map { "        - $it" }).joinToString("\n") + "\n"

    @Test
    fun growAndFadeActPerOccurrence() {
        val src = skill(
            "{id: puff, op: particle, particle: end_rod, anchor: caster, every: 2, for: 8, count: 4, extra: 0.1, motion: [{law: grow, prop: count, per_tick: 0.5}, {law: grow, prop: extra, per_tick: 0.01}]}",
            "{id: hum, op: sound, sound: block.beacon.ambient, anchor: caster, every: 2, for: 8, pitch: 0.5, motion: [{law: grow, prop: pitch, per_tick: 0.1}, {law: fade, prop: volume, for: 8}]}",
        )
        val loaded = loadInline(src)
        assertThat(loaded).`as`(loaded.toString()).isInstanceOf(Result.Success::class.java)
        val rows = (loaded as Result.Success).value.table.states.single().rows.map { it.action }
        val counts = rows.filterIsInstance<Action.Particle>().map { (it.count as Val.Known).value }
        assertThat(counts).containsExactly(4, 5, 6, 7)
        val extras = rows.filterIsInstance<Action.Particle>().map { (it.extra as Val.Known).value }
        assertThat(extras[3]).isCloseTo(0.16, Offset.offset(1e-9))
        val sounds = rows.filterIsInstance<Action.Sound>()
        assertThat(sounds.map { (it.pitch as Val.Known).value }[3]).isCloseTo(1.1, Offset.offset(1e-9))
        assertThat((sounds[0].volume as Val.Known).value).isCloseTo(1.0, Offset.offset(1e-9))
        assertThat((sounds[2].volume as Val.Known).value).isCloseTo(0.5, Offset.offset(1e-9))
    }

    @Test
    fun anOpLawThatIsNotDefinedOnPointKindsIsALoadError() {
        val src = skill("{id: puff, op: particle, particle: end_rod, anchor: caster, motion: [{law: spin, prop: offset, axis: up, deg_per_tick: 12}]}")
        assertThat(loadInline(src)).isInstanceOf(Result.Failure::class.java)
    }
}
