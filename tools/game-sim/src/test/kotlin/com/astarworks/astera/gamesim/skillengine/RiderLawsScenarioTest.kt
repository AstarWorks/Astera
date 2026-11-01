package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Rider laws (ADR-0303 rev4.13): a rider that outlives its state runs its laws for their own fixed length, and two laws writing one turn channel
 * over overlapping ticks are a load error instead of a silent drop. Read from the shipped couture-rapier-s2 (spin 4..11 on a 7-tick throw state,
 * +-35 deg tilt on the blades) and from an inline skill.
 */
class RiderLawsScenarioTest : SkillEngineScenario() {
    private fun shipped(): CompiledSkill {
        val text = Files.readString(contentDir.resolve("skills/couture-rapier-s2/skill.yaml"))
        return when (val r = compileText("couture-rapier-s2", text)) {
            is Result.Success -> r.value
            is Result.Failure -> error("couture-rapier-s2 must load: ${r.error.message}")
        }
    }

    /** The flying shears' display for [part] (body / blade_a / blade_b): the item display spawned at the release, tick 34. */
    private fun instanceOf(rows: List<String>, part: String) = rows.first { it.startsWith("@34 spawn ") && it.contains(" kind=item ") && it.contains(" part=$part ") }.split(' ')[2]

    /** The rotation the flying display of [id] carries at [tick]: its last spawn or write row at or before that tick (an unchanged display is not rewritten). */
    private fun rot(rows: List<String>, tick: Int, id: String): String {
        val inst = instanceOf(rows, id)
        return rows.filter { it.split(' ')[2] == inst && (it.contains(" write ") || it.contains(" spawn ")) && it.substringBefore(' ').drop(1).toInt() <= tick }
            .last().substringAfter(" rot=").substringBefore(' ')
    }

    @Test
    fun spinKeepsRunningPastTheThrowStateAndTheBladesKeepTheirTilt() {
        val rec = GatewayRecorder()
        play(shipped(), seed = 1L, events = emptyList(), ticks = 60, rec = rec)
        val rows = rec.oracleRows.map { "@${it.tick} ${it.verb} ${it.instance} ${it.payload} ${it.detail}" }
        // the volley hits at 36 and the throw state (34..41) is left early; the spin (body ticks 4..11 = 38..45) is the riders' own length and still plays
        assertThat(rot(rows, 39, "body")).isNotEqualTo(rot(rows, 41, "body"))
        assertThat(rot(rows, 41, "body")).isNotEqualTo(rot(rows, 44, "body"))
        // ...and ends where it was authored: held after body tick 11 (= 45)
        assertThat(rot(rows, 46, "body")).isEqualTo(rot(rows, 50, "body"))
        // the tilt: blade a and blade b turn opposite ways about their edge (not the body's rotation, not each other's)
        assertThat(rot(rows, 50, "blade_a")).isNotEqualTo(rot(rows, 50, "body"))
        assertThat(rot(rows, 50, "blade_a")).isNotEqualTo(rot(rows, 50, "blade_b"))
        // the cast end counts the riders' last write: they are still being written at 45, inside the machine tail
        val body = instanceOf(rows, "body")
        assertThat(rows.any { it.substringBefore(' ').drop(1).toInt() >= 45 && it.contains(" write ") && it.split(' ')[2] == body }).isTrue()
    }

    @Test
    fun twoLawsOnOneTurnChannelOverTheSameTicksAreALoadError() {
        val text = """
            apiVersion: astera/v3
            kind: Skill
            id: turn-clash
            machine:
              start: s
              states:
                s:
                  for: 12
                  do:
                    - {id: a, op: frame, anchor: caster.eye, for: 12, motion: [{law: align, axis: forward, to: world.up, up: caster.forward, at: 0, for: 6}]}
                    - {id: b, op: frame, anchor: a, for: 12, motion: [{law: spin, axis: up, deg_per_tick: 10, at: 3, for: 5}]}
        """.trimIndent()
        val r = loadInline(text)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        val msg = (r as Result.Failure).error.message
        assertThat(msg).contains("turn-clash").contains("rider 'b'").contains("turn channel").contains("0..6").contains("3..8")
    }
}
