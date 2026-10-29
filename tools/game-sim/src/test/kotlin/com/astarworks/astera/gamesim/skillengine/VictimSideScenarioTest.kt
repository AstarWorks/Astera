package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The victim side is first class (foundation audit G2 / P10): a `body` op names whose body plays (`target: event.victim`), and a projectile
 * `hit: {attach: true, attach_to: victim.feet}` sticks to the victim it hit, firing `stick` then `unstick` after `block.for` ticks.
 */
class VictimSideScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 11L)

    private fun skill(hit: String, on: String): String = """
        apiVersion: astera/v3
        kind: Skill
        id: victim-side
        anchors:
          - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}
        machine:
          start: s
          states:
            s:
              for: 40
              do:
                - id: bolt
                  op: projectile
                  at: 0
                  origin: muzzle
                  velocity: "= look(caster) * 1.0"
                  range: 10
                  speed: 1.0
                  radius: 0.4
                  hit: $hit
                  block: {mode: stick, for: 5}
                  on: $on
                - {id: trail, op: particle, particle: crit, anchor: bolt, at: 0, every: 1, for: 20}
    """.trimIndent()

    private fun load(hit: String, on: String): CompiledSkill = when (val r = loadInline(skill(hit, on))) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun fail(hit: String): SkillDecodeError = when (val r = loadInline(skill(hit, "{hit: [{id: snd, op: sound, at: 0, sound: \"astera:hit\", anchor: caster}]}"))) {
        is Result.Success -> error("expected a decode error")
        is Result.Failure -> r.error
    }

    private fun run(s: CompiledSkill, rec: GatewayRecorder = GatewayRecorder()): GatewayRecorder {
        rec.faithfulPose = true
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, 4.0), 0.0, HitBox(Vec3(-0.3, 0.0, 3.7), Vec3(0.3, 1.8, 4.3)), 1.62))
        play(s, seed = 1L, events = emptyList(), ticks = 30, rec = rec)
        return rec
    }

    private val attachOn = "{hit: [{id: ouch, op: damage, at: 0, target: event.victim, damage: {physical: 1.0}}], " +
        "stick: [{id: stuck_snd, op: sound, at: 0, sound: \"astera:stuck\", anchor: caster}], " +
        "unstick: [{id: free_snd, op: sound, at: 0, sound: \"astera:free\", anchor: caster}]}"

    @Test
    fun attachSticksToTheVictimAndUnsticksAfterBlockFor() {
        val s = load("{targets: enemies, once: true, attach: true, attach_to: victim.feet}", attachOn)
        val rec = run(s)
        val stuck = rec.calls.filter { it.verb == "sound" && it.payload.startsWith("sound=astera:stuck") }
        val free = rec.calls.filter { it.verb == "sound" && it.payload.startsWith("sound=astera:free") }
        assertThat(stuck).hasSize(1)
        assertThat(free).hasSize(1)
        assertThat(free.single().tick - stuck.single().tick).isEqualTo(5)
        // after the stick the trail rides the victim: its position stays at the victim's feet, not on the flight line
        val after = rec.emittedAt.filter { it.second == "crit" && it.first > stuck.single().tick && it.first < free.single().tick }
        assertThat(after).isNotEmpty
        assertThat(after.map { it.third.z }.toSet().size).describedAs("stuck body holds still on a still victim").isEqualTo(1)
        assertThat(after.first().third.z).isBetween(3.4, 4.2)
    }

    @Test
    fun attachFalseDoesNotStickToAnEntity() {
        val s = load("{targets: enemies, once: true, attach: false}", "{hit: [{id: snd, op: sound, at: 0, sound: \"astera:hit\", anchor: caster}]}")
        val rec = run(s)
        assertThat(rec.calls.filter { it.verb == "sound" }).hasSize(1) // the hit sound only: no stick, no unstick
    }

    @Test
    fun attachIsRequiredAndNeedsItsAnchorAndStickMode() {
        assertThat(fail("{targets: enemies, once: true}").toString()).contains("attach")
        assertThat(fail("{targets: enemies, once: true, attach: true}").toString()).contains("attach_to")
        assertThat(fail("{targets: enemies, once: true, attach: false, attach_to: victim.feet}").toString()).contains("attach_to")
        assertThat(fail("{targets: enemies, once: true, attach: true, attach_to: caster.eye}").toString()).contains("victim")
        assertThat(fail("{targets: enemies, once: false, times: 2, attach: true, attach_to: victim.feet}").toString()).contains("once")
    }

    @Test
    fun bodyTargetEventVictimPlaysTheClipOnTheVictim() {
        val s = load(
            "{targets: enemies, once: true, attach: false}",
            "{hit: [{id: victim_pose, op: body, at: 0, target: event.victim, anim: bastion-s1-body, speed: 1.0, for: 6}]}",
        )
        val rec = run(s)
        val plays = rec.calls.filter { it.verb == "effect" && it.payload.startsWith("body=bastion-s1-body") }
        assertThat(plays).hasSize(1)
        assertThat(plays.single().instance).isEqualTo(foe.toString())
        assertThat(rec.calls.filter { it.verb == "effect" && it.payload == "body_stop=bastion-s1-body" }.map { it.instance }).containsExactly(foe.toString())
    }

    @Test
    fun bodyWithoutTargetIsRefused() {
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: no-target
            machine:
              start: s
              states:
                s:
                  for: 10
                  do:
                    - {id: b, op: body, anim: bastion-s1-body, speed: 1.0, for: 6}
            """.trimIndent(),
        )
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error.toString()).contains("target")
    }
}
