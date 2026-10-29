package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Transition `when:` guards are compiled and type-checked at load (ADR-0303 3.8): a wrong arity, an unknown function or an unbound event field is a load error. */
class GuardCompileScenarioTest : SkillEngineScenario() {
    private fun skill(event: String, guard: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: guarded
        machine:
          start: a
          states:
            a:
              for: 40
              then: b
              on:
                - {event: $event, when: "$guard", to: b}
            b: {final: true}
    """.trimIndent()

    private fun errorOf(event: String, guard: String): String = when (val r = loadInline(skill(event, guard))) {
        is Result.Success -> error("expected a load error for '$guard' on $event")
        is Result.Failure -> r.error.message
    }

    @Test
    fun `a valid hurt guard loads`() {
        assertThat(loadInline(skill("hurt", "= in_arc(caster, pos(event.attacker), 45, 6)"))).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `wrong arity is a load error naming the expression`() {
        assertThat(errorOf("hurt", "= in_arc(caster, pos(event.attacker), 45)")).contains("in_arc")
    }

    @Test
    fun `unknown function is a load error`() {
        assertThat(errorOf("hurt", "= no_such_fn(1)")).contains("no_such_fn")
    }

    @Test
    fun `event attacker is unbound outside hurt`() {
        assertThat(errorOf("go", "= dist(caster, pos(event.attacker)) < 3")).contains("event").contains("state 'a' on[0] event 'go'")
    }

    @Test
    fun `non bool guard is a load error`() {
        assertThat(errorOf("hurt", "= 1 + 2")).isNotEmpty()
    }
}
