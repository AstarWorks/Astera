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
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W1b-02c acceptance (ADR-0303 10.2 rev4.2, wave1-resolutions items 7 and 14): sub-table keys are exactly `<state>.exit[<reason>]` and
 * `<state>.on[<k>]`, no bubbling, parallel regions, lock rows relative to the state base. Runs at the wave 1b gate.
 */
class MachineTablesScenarioTest : SkillEngineScenario() {
    private fun keys(s: CompiledSkill): Set<String> = s.table.subTables.keys.map { it.value }.toSet()

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

    // ---- W2-09: R35 patch ops, R36 nested start, R39 input events --------------------------------------------------------

    private fun failureOf(yaml: String): SkillDecodeError = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error
    }

    @Test
    fun nestedStartRequired() {
        val error = failureOf(
            """
            apiVersion: astera/v3
            kind: Skill
            id: nested-no-start
            machine:
              start: p
              states:
                p:
                  states:
                    c: { final: true }
            """.trimIndent(),
        )
        assertThat(error).isInstanceOf(SkillDecodeError.NestedStartMissing::class.java)
        assertThat((error as SkillDecodeError.NestedStartMissing).state).isEqualTo("p")
    }

    @Test
    fun startNamesChildIsLoadError() {
        val error = failureOf(
            """
            apiVersion: astera/v3
            kind: Skill
            id: start-child
            machine:
              start: p/c
              states:
                p:
                  start: c
                  states:
                    c: { final: true }
            """.trimIndent(),
        )
        assertThat(error).isInstanceOf(SkillDecodeError.StartNamesChild::class.java)
        assertThat((error as SkillDecodeError.StartNamesChild).start).isEqualTo("p/c")
    }

}
