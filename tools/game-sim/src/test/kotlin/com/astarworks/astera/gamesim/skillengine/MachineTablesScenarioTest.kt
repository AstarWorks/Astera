package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.MachineTables
import com.astarworks.astera.core.application.skillengine.compose.SectionLowerer
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W1b-02c acceptance (ADR-0303 10.2 rev4.2, wave1-resolutions items 7 and 14): sub-table keys are exactly `<state>.exit[<reason>]` and
 * `<state>.on[<k>]`, no bubbling, parallel regions, lock rows relative to the state base. Runs at the wave 1b gate.
 */
class MachineTablesScenarioTest : SkillEngineScenario() {
    private fun keys(s: CompiledSkill): Set<String> = s.table.subTables.keys.map { it.value }.toSet()

    private fun compiled(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    @Test
    fun exampleEKeySet() {
        // guard is a part call (no parent state after flattening): parry_window leaves by hurt and by timer(30); riposte / whiff / end by timer.
        val keys = keys(loadExample("e-couture-rapier-def-parry-part")).filter { it.contains(".exit[") || it.contains(".on[") }.toSet()
        assertThat(keys).containsExactlyInAnyOrder(
            "guard/parry_window.exit[timer]", "guard/parry_window.exit[event]", "guard/parry_window.exit[cancel]", "guard/parry_window.on[0]",
            "guard/riposte.exit[timer]", "guard/riposte.exit[cancel]",
            "guard/whiff.exit[timer]", "guard/whiff.exit[cancel]",
            "guard/end.exit[timer]", "guard/end.exit[cancel]",
        )
    }

    @Test
    fun exampleERiposteHitIsAbsentFromCancelExit() {
        val t = loadExample("e-couture-rapier-def-parry-part").table
        val timer = t.subTables.getValue(EventPath("guard/riposte.exit[timer]"))
        val cancel = t.subTables.getValue(EventPath("guard/riposte.exit[cancel]"))
        assertThat(timer.rows.first().action).isEqualTo(Action.Exit(StatePath("guard/riposte"), ExitReason.TIMER))
        assertThat(cancel.rows.first().action).isEqualTo(Action.Exit(StatePath("guard/riposte"), ExitReason.CANCEL))
        assertThat(timer.rows.any { it.action is Action.Damage }).isTrue()
        assertThat(cancel.rows.none { it.action is Action.Damage }).isTrue()
    }

    @Test
    fun exampleGKeySet() {
        val keys = keys(loadExample("g-couture-rapier-s2-states")).filter { it.contains(".exit[") || it.contains(".on[") }.toSet()
        assertThat(keys).containsExactlyInAnyOrder(
            "coin1.exit[timer]", "coin1.exit[cancel]",
            "raise.exit[timer]", "raise.exit[cancel]",
            "throw.exit[timer]", "throw.exit[event]", "throw.exit[cancel]", "throw.on[0]",
            "stuck.exit[timer]", "stuck.exit[cancel]",
            "coin3.exit[timer]", "coin3.exit[cancel]",
        )
    }

    @Test
    fun noBubblingNoHashKeys() {
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: no-bubble
            machine:
              start: p
              states:
                p:
                  for: 30
                  then: z
                  on: [ { event: stop, to: z } ]
                p/c: { for: 5, then: p/d }
                p/d: { final: true }
                z: { final: true }
            """.trimIndent(),
        )
        assertThat(keys(s).none { it.contains('#') || it.contains("/p#") }).isTrue()
        // the child holds only its own branch; the ancestor's event and timer stay on the ancestor
        val child = s.table.states.first { it.state == StatePath("p/c") }
        assertThat(child.branches.map { it.event.value }).containsExactly("timer(5)")
        val parent = s.table.states.first { it.state == StatePath("p") }
        assertThat(parent.branches.map { it.event.value }).containsExactlyInAnyOrder("stop", "timer(30)")
        // the child can be preempted, so it has exit[preempt]; the parent has no preempt (no parent of its own)
        assertThat(keys(s)).contains("p/c.exit[preempt]", "p/c.exit[timer]", "p/c.exit[cancel]", "p.exit[event]", "p.exit[timer]")
        assertThat(keys(s)).doesNotContain("p.exit[preempt]")
        // the parent enters its direct child at the entry tick
        val enters = parent.rows.filter { it.action is Action.Enter }.map { (it.action as Action.Enter).state }
        assertThat(enters).containsExactly(StatePath("p"), StatePath("p/c"))
    }

    @Test
    fun parallelRegionsAreEnteredByTheParent() {
        val noOps = SectionLowerer { _, _, _ -> Result.Failure(SkillDecodeError.Invalid("par", "x", "no ops expected")) }
        fun state(id: String, final: Boolean = false, parallel: List<Machine> = emptyList()) =
            MachineState(id, null, null, final, emptyList(), emptyList(), emptyList(), emptyList(), null, parallel)
        fun region(vararg ids: String) = Machine(ids.first(), ids.associateWith { state(it, final = true) })
        val machine = Machine(
            "par",
            mapOf("par" to state("par", parallel = listOf(region("a"), region("b"))), "z" to state("z", final = true)),
        )
        val m = (MachineTables.lower("par", machine, noOps, EngineConfig()) as Result.Success).value
        val par = m.states.first { it.state == StatePath("par") }
        assertThat(par.rows.filter { it.action is Action.Enter }.map { (it.action as Action.Enter).state })
            .containsExactly(StatePath("par"), StatePath("par/a"), StatePath("par/b"))
        // each final region state emits the parent's end event (the player counts all regions, TablePlayer.levelFinal)
        val a = m.states.first { it.state == StatePath("par/a") }
        assertThat(a.rows.map { it.action }).contains(Action.Emit(EventPath("par.end")))
        assertThat(m.subTables.keys.map { it.value }).contains("par/a.exit[preempt]", "par/b.exit[preempt]", "par/a.exit[cancel]")
    }

    @Test
    fun lockRowIsRelativeToTheStateBase() {
        val s = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: lock-base
            machine:
              start: first
              states:
                first: { for: 10, then: second }
                second:
                  for: 20
                  then: done
                  do:
                    - {id: plant, op: lock, move: all, at: 3, for: 5}
                done: { final: true }
            """.trimIndent(),
        )
        val rows = s.table.states.single { it.state == StatePath("second") }.rows
        val lock = rows.single { it.action is Action.Lock }
        // tick 3 of the state; held until tick 8 of the state, not of the cast (the state is entered at cast tick 10)
        assertThat(lock.tick).isEqualTo(3)
        assertThat((lock.action as Action.Lock).until).isEqualTo(LockEnd.AtTick(8))
    }
}
