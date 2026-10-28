package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 11.1 (R17): `seq:` is sugar; it expands to the byte-identical table of the declarative `at:` form. */
class SeqSugarScenarioTest : SkillEngineScenario() {
    private fun skill(doOps: String) = "apiVersion: astera/v3\nkind: Skill\nid: seq-equality\nmachine:\n  start: charge\n  states:\n    charge:\n      for: 19\n      then: end\n      do:\n" +
        doOps + "    end: {final: true}\n"

    private fun render(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> ExpansionDump.render(r.value.table)
        is Result.Failure -> throw AssertionError("did not load: ${r.error}")
    }

    private val chime = "op: sound, sound: block.amethyst_block.chime, anchor: caster, volume: 0.5"
    private val orb = "op: display, kind: sprite, sprite: couture-s2-haze-soft, anchor: caster, size: [0.4, 0.4]"
    private val flash = "op: display, kind: sprite, sprite: ring, anchor: caster, size: [1.0, 1.0]"

    @Test
    fun seqAndDeclarativeAtExpandToTheSameTable() {
        val declarative = skill(
            "        - {id: orb, $orb, at: 0, for: 16}\n" +
                "        - {id: s0, $chime, pitch: 1.0, at: 0}\n" +
                "        - {id: s1, $chime, pitch: 1.1, at: 4}\n" +
                "        - {id: s2, $chime, pitch: 1.2, at: 8}\n" +
                "        - {id: s3, $chime, pitch: 1.3, at: 12}\n" +
                "        - {id: flash, $flash, at: 16, for: 3}\n",
        )
        val sugar = skill(
            "        - seq:\n" +
                "            - {id: orb, $orb, for: 16}\n" +
                "            - {id: s0, $chime, pitch: 1.0}\n" +
                "            - {wait: 4}\n" +
                "            - {id: s1, $chime, pitch: 1.1}\n" +
                "            - {wait: 4}\n" +
                "            - {id: s2, $chime, pitch: 1.2}\n" +
                "            - {wait: 4}\n" +
                "            - {id: s3, $chime, pitch: 1.3}\n" +
                "            - {wait: 4}\n" +
                "            - {id: flash, $flash, for: 3}\n",
        )
        val expected = render(declarative)
        assertThat(expected).contains("chime")
        assertThat(render(sugar)).isEqualTo(expected)
    }
}
