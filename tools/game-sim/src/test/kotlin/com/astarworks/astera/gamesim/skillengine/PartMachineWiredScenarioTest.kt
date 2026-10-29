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

    // ---- skill-local fragments (ADR-0303 rev4.10, batch G) ---------------------------------------------------------------

    private fun fragmentSkill(fragments: String, hitHandler: String, missHandler: String = "[]") = "apiVersion: astera/v3\nkind: Skill\nid: frag-skill\n" + fragments + "\n" + """
        machine:
          start: slash
          states:
            slash:
              for: 3
              do:
                - id: cut
                  op: hit
                  at: 0
                  anchor: caster
                  shape: {sphere: 8.0}
                  targets: enemies
                  ignore_invulnerability: false
                  on:
                    hit: $hitHandler
                    miss: $missHandler
    """.trimIndent()

    private val burst = """
        fragments:
          burst:
            params: {n: {type: number, default: 6}}
            do:
              - {id: spark, op: particle, particle: crit, anchor: caster, count: "= n", at: 0}
    """.trimIndent()

    private fun particleCounts(yaml: String, withVictim: Boolean): List<String> {
        val skill = when (val r = loadInline(yaml)) { is Result.Success -> r.value; is Result.Failure -> throw AssertionError("did not load: ${r.error}") }
        val rec = GatewayRecorder()
        if (withVictim) rec.victims = listOf(com.astarworks.astera.core.domain.skillengine.hit.HitCandidate(java.util.UUID(0L, 11L), com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 2.0), 0.0, com.astarworks.astera.core.domain.skillengine.hit.HitBox(com.astarworks.astera.core.domain.geometry.Vec3(-0.3, 0.0, 1.7), com.astarworks.astera.core.domain.geometry.Vec3(0.3, 1.8, 2.3))))
        return play(skill, seed = 1L, events = emptyList(), ticks = 4, rec = rec).filter { it.verb == "particle" }.map { it.payload }
    }

    @Test
    fun fragmentFromHitAndMissHandlers() {
        val yaml = fragmentSkill(burst, "[{op: use, use: 'fragment:burst', with: {n: 10}}]", "[{op: use, use: 'fragment:burst', with: {n: 3}}]")
        val hit = particleCounts(yaml, withVictim = true)
        val miss = particleCounts(yaml, withVictim = false)
        assertThat(hit).hasSize(1)
        assertThat(hit.single()).contains("count=10")
        assertThat(miss).hasSize(1)
        assertThat(miss.single()).contains("count=3")
    }

    @Test
    fun fragmentRecursionIsLoadError() {
        val yaml = fragmentSkill(
            """
            fragments:
              burst:
                do: [{op: use, use: 'fragment:ring'}]
              ring:
                do: [{op: use, use: 'fragment:burst'}]
            """.trimIndent(),
            "[{op: use, use: 'fragment:burst'}]",
        )
        val r = loadInline(yaml)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error.message).contains("fragment 'burst' calls itself (burst -> ring -> burst)")
    }

    @Test
    fun undeclaredFragmentIsLoadError() {
        val yaml = fragmentSkill(
            """
            fragments:
              ring: {do: [{id: p, op: particle, particle: crit, anchor: caster, count: 1, at: 0}]}
              spark: {do: [{id: q, op: particle, particle: crit, anchor: caster, count: 1, at: 0}]}
            """.trimIndent(),
            "[{op: use, use: 'fragment:burst'}]",
        )
        val r = loadInline(yaml)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error.message).contains("use 'fragment:burst': this skill declares no fragment 'burst' (fragments: ring, spark)")
    }
}
