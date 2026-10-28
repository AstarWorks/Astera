package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
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
            id: s1
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
            id: s2
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
            id: s3
            machine:
              start: p/c
              states:
                p: { on: [ { event: stop, to: z } ] }
                p/c: { }
                z: { final: true }
            """.trimIndent(),
        )
        val child = t.states.first { it.state == StatePath("p/c") }
        val stop = child.branches.first { it.event == EventPath("stop") }
        assertThat(stop.target).isEqualTo(StatePath("z"))
    }

    @Test
    fun preemptionExitsChildrenInnermostFirst() {
        val t = compiled(
            """
            id: s4
            machine:
              start: p/q/c
              states:
                p: { on: [ { event: stop, to: z } ] }
                p/q: { }
                p/q/c: { }
                z: { final: true }
            """.trimIndent(),
        )
        val sub = t.subTables.values.first { it.event.value.startsWith("p/q/c.exit[preempt]/p") }
        val exits = sub.rows.map { it.action }.filterIsInstance<Action.Exit>()
        assertThat(exits.map { it.state to it.reason }).containsExactly(
            StatePath("p/q/c") to ExitReason.PREEMPT, StatePath("p/q") to ExitReason.PREEMPT, StatePath("p") to ExitReason.EVENT,
        )
    }

    @Test
    fun parallelRegionsEndWhenAllFinal() {
        // Needs MachineState.parallel (request to W0-01): the parent ends when every region has emitted `<region>.end`.
        val t = compiled(
            """
            id: s5
            machine:
              start: par
              states:
                par: { parallel: [ra, rb], on: [ { event: par.end, to: z } ] }
                ra: { final: true }
                rb: { final: true }
                z: { final: true }
            """.trimIndent(),
        )
        assertThat(t.states.first { it.state == StatePath("par") }.branches.any { it.event == EventPath("par.end") }).isTrue()
    }
}
