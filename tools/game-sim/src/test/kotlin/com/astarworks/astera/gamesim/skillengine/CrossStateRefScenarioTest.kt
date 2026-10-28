package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0303 rev4.6: a later state names an instance an earlier state spawned as `/<state>/<id>`; an instance gone by then is a named
 * load error, a bare name never looks into another state. `ribbon` takes `until:`; `held_model` swaps the caster's weapon model.
 */
class CrossStateRefScenarioTest : SkillEngineScenario() {

    private fun skill(aFor: Int, aDo: String, bDo: String, span: String = "until: b.end"): String = """
        apiVersion: astera/v3
        kind: Skill
        id: cross-probe
        item: any
        machine:
          start: a
          states:
            a:
              for: $aFor
              then: b
              do:
                - {id: f, op: frame, anchor: caster.hand_r, ${span}}
                $aDo
            b:
              for: 20
              do:
                - $bDo
        """.trimIndent() + "\n"

    private fun load(y: String) = loadInline(y)

    private fun compile(y: String): CompiledSkill = when (val r = load(y)) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("expected to load: ${r.error.message}")
    }

    private fun msgOf(y: String): String = (load(y) as? Result.Failure)?.error?.message ?: "loaded"

    private val use = "{op: particle_def, def: couture-s2-p-stick, anchor: %s, at: 0, every: 1, times: 3}"

    @Test
    fun qualifiedRefReachesAnInstanceOfAnEarlierState() {
        compile(skill(30, "", use.format("/a/f")))
    }

    @Test
    fun refToAnEndedInstanceIsANamedError() {
        val msg = msgOf(skill(30, "", use.format("/a/f"), span = "for: 10"))
        assertThat(msg).contains("/a/f").contains("gone")
    }

    @Test
    fun bareNameNeverLooksIntoAnotherState() {
        assertThat(msgOf(skill(30, "", use.format("f")))).contains("not declared")
    }

    @Test
    fun lineRibbonUntilEndsAtTheEventSubTable() {
        val y = """
            apiVersion: astera/v3
            kind: Skill
            id: ribbon-until
            item: any
            machine:
              start: s
              states:
                s:
                  for: 40
                  then: e
                  do:
                    - {id: f1, op: frame, anchor: caster.hand_r, for: 40}
                    - {id: f2, op: frame, anchor: caster.hand_l, for: 40}
                    - {id: r, op: ribbon, at: 0, from: f1, to: f2, thickness: 0.1, color: "#ffffff", until: e.end}
                e:
                  for: 5
        """.trimIndent() + "\n"
        val s = compile(y)
        val rows = s.table.states.flatMap { it.rows }
        assertThat(rows.none { it.action is Action.End && it.instance.path.last().op.value.endsWith("r") }).`as`("no static End").isTrue()
        assertThat(s.table.subTables.values.flatMap { it.rows }.any { it.action is Action.End && it.instance.path.last().op.value.endsWith("r") }).isTrue()
    }

    @Test
    fun heldModelSwapsAtOnsetAndRestoresAtTheEnd() {
        val y = """
            apiVersion: astera/v3
            kind: Skill
            id: held-probe
            item: any
            machine:
              start: s
              states:
                s:
                  for: 70
                  do:
                    - {op: held_model, at: 20, model: "astera:hidden", for: 37}
        """.trimIndent() + "\n"
        val calls = play(compile(y), 1L).filter { it.verb == "effect" && it.payload.startsWith("held_model") }
        assertThat(calls.map { it.tick to it.payload }).containsExactly(20 to "held_model=astera:hidden", 57 to "held_model_clear")
        assertThat(msgOf(y.replace(", for: 37", ""))).isNotEqualTo("loaded")
    }
}
