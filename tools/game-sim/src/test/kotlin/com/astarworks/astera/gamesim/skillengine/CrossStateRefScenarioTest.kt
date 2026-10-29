package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
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

    private fun msgOf(y: String): String = (load(y) as? Result.Failure)?.error?.message ?: "loaded"

    private val use = "{op: particle_def, def: couture-s2-p-stick, anchor: %s, at: 0, every: 1, times: 3}"

    @Test
    fun refToAnEndedInstanceIsANamedError() {
        val msg = msgOf(skill(30, "", use.format("/a/f"), span = "for: 10"))
        assertThat(msg).contains("/a/f").contains("gone")
    }

    @Test
    fun bareNameNeverLooksIntoAnotherState() {
        assertThat(msgOf(skill(30, "", use.format("f")))).contains("not declared")
    }

}
