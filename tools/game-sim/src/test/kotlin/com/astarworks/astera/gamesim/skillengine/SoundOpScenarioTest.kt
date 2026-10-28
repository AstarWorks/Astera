package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Val
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
        presentation:
          timeline:
            - id: fx0
              at: 0
              do:
                - $soundLine
        """.trimIndent()

    private fun soundRows(skill: CompiledSkill): List<Action.Sound> =
        skill.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.Sound>()

    private fun loaded(yaml: String): CompiledSkill {
        val r = loadInline(yaml)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        return (r as Result.Success).value
    }

    @Test
    fun soundDefaultsAreVanillaIdentity() {
        val rows = soundRows(loaded(skill("{id: snd, op: sound, sound: entity.player.attack.sweep, anchor: body}")))
        assertThat(rows).hasSize(1)
        val s = rows.single()
        assertThat(s.sound).isEqualTo("entity.player.attack.sweep")
        assertThat(s.volume).isEqualTo(Val.Known(1.0))
        assertThat(s.pitch).isEqualTo(Val.Known(1.0))
        assertThat(s.category).isEqualTo("master")
    }

    @Test
    fun literalVolumePitchAndCategoryAreKnown() {
        val rows = soundRows(loaded(skill("{id: snd, op: sound, sound: block.stone.hit, anchor: body, volume: 0.5, pitch: 1.4, category: blocks}")))
        val s = rows.single()
        assertThat(s.volume).isEqualTo(Val.Known(0.5))
        assertThat(s.pitch).isEqualTo(Val.Known(1.4))
        assertThat(s.category).isEqualTo("blocks")
    }

    @Test
    fun expressionPitchStaysLiveUntilPlayed() {
        val rows = soundRows(loaded(skill("{id: snd, op: sound, sound: item.armor.equip_leather, anchor: body, every: 2, times: 3, pitch: \"= 0.9 + 0.08 * i\"}")))
        assertThat(rows.single().pitch).isEqualTo(Val.Live<Double>("= 0.9 + 0.08 * i", null))
    }

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
