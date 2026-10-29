package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0303 rev4.10: a machine timer (state `for`, transition `after`, `seq` wait) takes `"${param}"`, folded at load from the param's value
 * (default, call argument or a patched default) before the expansion, like every other tick field. Real loader, real table; the table text of a
 * `${ticks}` skill must equal the literal one.
 */
class TimerParamScenarioTest : SkillEngineScenario() {
    private fun skill(ticks: String, decl: String = "ticks: {type: number, default: 6}", stateFor: String = "\"\${ticks}\"", after: String = "\"= ticks + 1\"", wait: String = "\"\${ticks}\"") = """
        apiVersion: astera/v3
        kind: Skill
        id: timer-param
        params: {$decl}
        machine:
          start: a
          states:
            a:
              for: $stateFor
              then: b
              do:
                - {id: mark_a, op: status, target: caster, status: shock, stacks: 1, for: 3}
            b:
              on:
                - {after: $after, to: c}
              do:
                - seq:
                    - {wait: $wait}
                    - {id: mark_b, op: status, target: caster, status: shock, stacks: 1, for: 3}
            c:
              final: true
              enter:
                - {id: mark_c, op: status, target: caster, status: shock, stacks: 1, for: 3}
        """.trimIndent().replace("<ticks>", ticks)

    private fun table(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> ExpansionDump.render(r.value.table)
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun literal(n: Int) = skill("", decl = "other: {type: number, default: 1}", stateFor = "$n", after = "${n + 1}", wait = "$n")

    @Test
    fun paramTimersEqualLiteralRows() {
        assertThat(table(skill(""))).isEqualTo(table(literal(6)))
    }

    @Test
    fun aPatchedDefaultChangesEveryTimerLength() {
        // a balance / skin patch rewrites params.ticks.default before the decode: the composed tree is this document
        val patched = skill("", decl = "ticks: {type: number, default: 9}")
        assertThat(table(patched)).isEqualTo(table(literal(9)))
        assertThat(table(patched)).isNotEqualTo(table(skill("")))
    }

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.toString()
    }

    @Test
    fun undeclaredNegativeAndNonIntegerAreLoadErrorsNamingTheField() {
        assertThat(failure(skill("", decl = "other: {type: number, default: 6}", after = "6", wait = "6"))).contains("for")
        assertThat(failure(skill("", decl = "ticks: {type: number, default: -1}", after = "6", wait = "6"))).contains("machine.states.a.for")
        assertThat(failure(skill("", decl = "ticks: {type: number, default: 2.5}", after = "6", wait = "6"))).contains("machine.states.a.for")
        assertThat(failure(skill("", decl = "ticks: {type: string, default: slow}", after = "6", wait = "6"))).contains("machine.states.a.for")
        assertThat(failure(skill("", stateFor = "6", after = "\"\${nope}\""))).contains("after")
        assertThat(failure(skill("", stateFor = "6", after = "-3"))).contains("after")
    }
}
