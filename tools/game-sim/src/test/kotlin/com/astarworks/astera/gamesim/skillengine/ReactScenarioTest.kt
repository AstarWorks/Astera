package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `op: react` (design-gaps-v1 1.2 to 1.5): the hit victim's body reaction. flinch / push / hold work on any victim; the `player:` clip plays only
 * for a player and is skipped by name (`react_skip`) for a mob.
 */
class ReactScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 21L)

    private fun skill(react: String): String = """
        apiVersion: astera/v3
        kind: Skill
        id: react-probe
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
                  hit: {targets: enemies, once: true, attach: false}
                  block: {mode: stick, for: 5}
                  on: {hit: [$react]}
    """.trimIndent()

    private fun load(react: String): CompiledSkill = when (val r = loadInline(skill(react))) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun refused(react: String): String = when (val r = loadInline(skill(react))) {
        is Result.Success -> error("expected a decode error")
        is Result.Failure -> r.error.toString()
    }

    private fun run(s: CompiledSkill, mob: Boolean): GatewayRecorder {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        if (mob) rec.mobs += foe
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, 4.0), 0.0, HitBox(Vec3(-0.3, 0.0, 3.7), Vec3(0.3, 1.8, 4.3)), 1.62))
        play(s, seed = 1L, events = emptyList(), ticks = 30, rec = rec)
        return rec
    }

    private val full = "{id: r, op: react, target: event.victim, flinch: true, push: {back: 0.5, up: 0.2}, hold: 4, player: {anim: bastion-s1-body}, for: 6}"

    @Test
    fun mobPlaysFlinchPushAndHoldButSkipsTheClip() {
        val rec = run(load(full), mob = true)
        val effects = rec.calls.filter { it.verb == "effect" && it.instance == foe.toString() }.map { it.payload.substringBefore(' ') }
        assertThat(effects).contains("hurt", "impulse", "freeze=4")
        assertThat(effects.none { it.startsWith("body") }).isTrue()
        assertThat(rec.calls.filter { it.verb == "react_skip" }).hasSize(1)
    }

    @Test
    fun playerPlaysTheClipAndEndsItAfterFor() {
        val rec = run(load(full), mob = false)
        val plays = rec.calls.filter { it.verb == "effect" && it.payload.startsWith("body=bastion-s1-body") }
        assertThat(plays).hasSize(1)
        assertThat(plays.single().instance).isEqualTo(foe.toString())
        val stop = rec.calls.filter { it.verb == "effect" && it.payload == "body_stop=bastion-s1-body" }
        assertThat(stop).hasSize(1)
        assertThat(stop.single().tick - plays.single().tick).isEqualTo(6)
        assertThat(rec.calls.filter { it.verb == "react_skip" }).isEmpty()
    }

    @Test
    fun pushGoesAwayFromTheCaster() {
        val rec = run(load("{id: r, op: react, target: event.victim, push: {back: 0.5, up: 0.2}, for: 6}"), mob = true)
        val imp = rec.calls.single { it.verb == "effect" && it.payload.startsWith("impulse") }.payload
        assertThat(imp).contains("z=0.5").contains("y=0.2")
    }

    @Test
    fun loadErrors() {
        assertThat(refused("{id: r, op: react, target: event.victim, flinch: true, hold: 9, for: 6}")).contains("hold")
        assertThat(refused("{id: r, op: react, target: event.victim, flinch: true}")).contains("for")
        assertThat(refused("{id: r, op: react, target: event.victim, flinch: false, push: {back: 1}, for: 6}")).contains("flinch")
        assertThat(refused("{id: r, op: react, target: event.victim, push: {forward: 1}, for: 6}")).contains("back")
        assertThat(refused("{id: r, op: react, target: event.victim, hold: 2.5, for: 6}")).contains("hold")
        assertThat(refused("{id: r, op: react, target: caster, flinch: true, for: 6}")).contains("victim")
    }
}
