package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 10: machine -> tables (W1-16). Runs at the wave 1b gate (needs W1-18 decoder + W1-20 harness). */
class PartMachineWiredScenarioTest : SkillEngineScenario() {
    private fun compiled(yaml: String) = when (val r = loadInline(yaml)) { is Result.Success -> r.value.table; is Result.Failure -> throw AssertionError("did not load: ${r.error}") }

    @Test
    fun parallelRegionsEndWhenAllFinal() {
        // ADR-0303 10.2 `parallel: [machine, ...]`: the parent ends when every region has emitted `<region>.end`.
        val t = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: s5
            meta: {cooldown_ticks: 0, resource_cost: 0}
            machine:
              start: par
              states:
                par:
                  parallel:
                    - { start: ra, states: { ra: { final: true } } }
                    - { start: rb, states: { rb: { final: true } } }
                  on: [ { event: par.end, to: z } ]
                z: { final: true }
            """.trimIndent(),
        )
        assertThat(t.states.first { it.state == StatePath("par") }.branches.any { it.event == EventPath("par.end") }).isTrue()
    }
}
