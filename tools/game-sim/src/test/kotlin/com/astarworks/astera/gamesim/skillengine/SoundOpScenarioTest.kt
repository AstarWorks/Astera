package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** W1-05 `sound` op: vanilla defaults, live expressions, and load-time rejection. Runs once the W1-20 harness is wired. */
class SoundOpScenarioTest : SkillEngineScenario() {
    private fun skill(soundLine: String): String = """
        apiVersion: astera/v3
        kind: Skill
        id: sound-probe
        anchors:
          - {id: body, anchor: caster}
        machine:
          start: s
          states:
            s:
              for: 20
              do:
                - $soundLine
        """.trimIndent()

    @Test
    fun undeclaredAnchorIsInvalid() {
        val r = loadInline(skill("{id: snd, op: sound, sound: block.stone.hit, anchor: nowhere}"))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.Invalid::class.java)
    }

    @Test
    fun missingAnchorIsRejected() {
        val r = loadInline(skill("{id: snd, op: sound, sound: block.stone.hit}"))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error).isInstanceOf(SkillDecodeError.MissingField::class.java)
    }

    @Test
    fun unknownCategoryIsRejected() {
        val r = loadInline(skill("{id: snd, op: sound, sound: block.stone.hit, anchor: body, category: loud}"))
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }
}
