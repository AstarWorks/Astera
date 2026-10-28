package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 10: machine -> tables (W1-16). Runs at the wave 1b gate (needs W1-18 decoder + W1-20 harness). */
class PartMachineWiredScenarioTest : SkillEngineScenario() {
    private fun compiled(yaml: String) = (loadInline(yaml) as Result.Success).value.table

    @Test
    fun statesForThenTimer() {
        val t = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: s1
            meta: {cooldown_ticks: 0, resource_cost: 0}
            machine:
              start: a
              states:
                a: { for: 10, then: b }
                b: { final: true }
            """.trimIndent(),
        )
        val a = t.states.first { it.state == StatePath("a") }
        assertThat(a.duration).isEqualTo(Duration.Fixed(10))
        assertThat(a.then).isEqualTo(StatePath("b"))
        assertThat(a.branches.any { it.event == EventPath("timer(10)") && it.target == StatePath("b") }).isTrue()
    }

    @Test
    fun transitionOrderFirstMatch() {
        val t = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: s2
            meta: {cooldown_ticks: 0, resource_cost: 0}
            machine:
              start: a
              states:
                a:
                  on:
                    - { event: hit, when: "= true", to: b }
                    - { event: hit, to: c }
                b: { final: true }
                c: { final: true }
            """.trimIndent(),
        )
        val hits = t.states.first { it.state == StatePath("a") }.branches.filter { it.event == EventPath("hit") }
        assertThat(hits.map { it.target }).containsExactly(StatePath("b"), StatePath("c"))
    }

    @Test
    fun eventBubblesChildToParent() {
        val t = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: s3
            meta: {cooldown_ticks: 0, resource_cost: 0}
            machine:
              start: p
              states:
                p:
                  for: 30
                  then: z
                  on: [ { event: input.use, to: z } ]
                  start: c
                  states:
                    c: { for: 5, then: d }
                    d: { final: true }
                z: { final: true }
            """.trimIndent(),
        )
        // the parent holds the branch; the player offers the event innermost first and bubbles it (item 14.8)
        assertThat(t.states.first { it.state == StatePath("p") }.branches.first { it.event == EventPath("input.use") }.target).isEqualTo(StatePath("z"))
        assertThat(t.states.first { it.state == StatePath("p/c") }.branches.none { it.event == EventPath("input.use") }).isTrue()
    }

    @Test
    fun preemptionExitsChildrenInnermostFirst() {
        val t = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: s4
            meta: {cooldown_ticks: 0, resource_cost: 0}
            machine:
              start: p
              states:
                p:
                  for: 30
                  then: z
                  on: [ { event: input.use, to: z } ]
                  start: q
                  states:
                    q:
                      for: 20
                      then: r
                      start: c
                      states:
                        c: { for: 5, then: d }
                        d: { final: true }
                    r: { final: true }
                z: { final: true }
            """.trimIndent(),
        )
        // one exit sub-table per state and reason; the paste order (descendants innermost first, then own exit, then on[k]) is the player's
        for (key in listOf("p/q/c.exit[preempt]", "p/q.exit[preempt]", "p.exit[event]")) {
            val sub = t.subTables[EventPath(key)]
            assertThat(sub).describedAs(key).isNotNull
            val first = sub!!.rows.first().action as Action.Exit
            assertThat(first.state.value + ".exit[" + first.reason.name.lowercase() + "]").isEqualTo(key)
        }
    }

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
