package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 3.4: particles and sounds take `motion:` property laws, evaluated once per occurrence at `tau = i * every`. */
class PointLawsScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String): String = (listOf(
        "apiVersion: astera/v3", "kind: Skill", "id: point-laws-probe", "item: couture-rapier",
        "machine:", "  start: s", "  states:", "    s:", "      for: 10", "      do:",
    ) + ops.map { "        - $it" }).joinToString("\n") + "\n"

    @Test
    fun anOpLawThatIsNotDefinedOnPointKindsIsALoadError() {
        val src = skill("{id: puff, op: particle, particle: end_rod, anchor: caster, motion: [{law: spin, prop: offset, axis: up, deg_per_tick: 12}]}")
        assertThat(loadInline(src)).isInstanceOf(Result.Failure::class.java)
    }
}
