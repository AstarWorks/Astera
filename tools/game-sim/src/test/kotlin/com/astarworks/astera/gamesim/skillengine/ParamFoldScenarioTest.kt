package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0303 rev4.10: one load-time rule for a param-valued cell. `"${p}"` and `"= CEL over params"` fold to a number or an id before the expansion,
 * a state `for` takes either, an int literal compares with a double param (`tier == 2`), and a `${param}` id (sound, particle_def) is the param's value.
 * Real loader, real table: the folded skill must dump the same rows as its literal twin.
 */
class ParamFoldScenarioTest : SkillEngineScenario() {
    private fun skill(params: String, stateFor: String, ops: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: param-fold
        params: {$params}
        machine:
          start: a
          states:
            a:
              for: $stateFor
              then: b
              do:
                - $ops
            b:
              final: true
        """.trimIndent()

    private fun table(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> ExpansionDump.render(r.value.table)
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.toString()
    }

    private val mark = "{id: m, op: status, target: caster, status: shock, stacks: 1, for: 3, at: 1}"

    @Test
    fun celStateForFoldsToTheLiteralRows() {
        val folded = skill("fly: {type: number, default: 6}", "\"= fly + 1\"", mark)
        val literal = skill("other: {type: number, default: 1}", "7", mark)
        assertThat(table(folded)).isEqualTo(table(literal))
        // a patched default moves the folded length
        assertThat(table(skill("fly: {type: number, default: 9}", "\"= fly + 1\"", mark)))
            .isEqualTo(table(skill("other: {type: number, default: 1}", "10", mark)))
    }

    @Test
    fun celStateForThatIsNotAWholeNumberIsALoadErrorNamingTheField() {
        assertThat(failure(skill("fly: {type: number, default: 2.5}", "\"= fly + 1\"", mark))).contains("machine.states.a.for")
        assertThat(failure(skill("fly: {type: number, default: 0}", "\"= fly - 1\"", mark))).contains("machine.states.a.for")
    }

    @Test
    fun anIntLiteralComparesWithADoubleParam() {
        val op = "{id: m, op: status, target: caster, status: shock, stacks: 1, for: 3, at: 1, when: \"= tier == 2\"}"
        assertThat(table(skill("tier: {type: number, default: 2}", "6", op))).isNotBlank()
        val ne = "{id: m, op: status, target: caster, status: shock, stacks: 1, for: 3, at: 1, when: \"= tier != 2 && tier >= 1\"}"
        assertThat(table(skill("tier: {type: number, default: 3}", "6", ne))).isNotBlank()
    }

    @Test
    fun hitShapeSizeTakesAParam() {
        fun hit(radius: String) = "{id: sel, op: hit, at: 2, anchor: caster, shape: {sphere: $radius}, targets: enemies, ignore_invulnerability: false, damage: {physical: 1.0}}"
        assertThat(table(skill("r: {type: number, default: 8}", "6", hit("\"\${r}\""))))
            .isEqualTo(table(skill("other: {type: number, default: 1}", "6", hit("8.0"))))
    }

    @Test
    fun soundAndParticleDefIdsTakeAStringParam() {
        fun sound(id: String) = "{id: s, op: sound, sound: $id, anchor: caster, at: 0}"
        assertThat(table(skill("snd: {type: string, default: block.note_block.hat}", "6", sound("\"\${snd}\""))))
            .isEqualTo(table(skill("other: {type: number, default: 1}", "6", sound("block.note_block.hat"))))
        fun def(id: String) = "{id: p, op: particle_def, def: $id, anchor: caster, at: 0}"
        assertThat(table(skill("fx: {type: string, default: hit-spark}", "6", def("\"\${fx}\""))))
            .isEqualTo(table(skill("other: {type: number, default: 1}", "6", def("hit-spark"))))
        // a numeric param where an id is expected, and an undeclared one, are load errors naming the field
        assertThat(failure(skill("fx: {type: number, default: 3}", "6", def("\"\${fx}\"")))).contains("def")
        assertThat(failure(skill("other: {type: number, default: 1}", "6", def("\"\${nope}\"")))).contains("nope")
    }
}
