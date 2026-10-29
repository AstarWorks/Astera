package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Engine leftovers: a timing field that does not fold to a whole number is a load error naming the field and the value (no rounding), a
 * `break_area` sphere has no `ahead` key, and a `hit` anchored on a per-tick-moving projectile sweeps the true one-tick segment.
 */
class EngineFollowupScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 11L)

    private fun loadError(yaml: String): String = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.toString()
    }

    private fun timed(at: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: timing
        params:
          d: {type: number, default: 2.5}
        machine:
          start: s
          states:
            s:
              for: 10
              do:
                - {id: snd, op: sound, sound: "astera:x", anchor: caster, at: $at}
    """

    @Test
    fun aLiteralTimingThatIsNotWholeIsALoadError() {
        assertThat(loadError(timed("2.5"))).contains("whole number").contains("2.5")
    }

    @Test
    fun anExpressionTimingThatFoldsToANonWholeNumberIsALoadError() {
        assertThat(loadError(timed("\"= 2.5 * 1\""))).contains("whole number").contains("2.5")
    }

    @Test
    fun aWholeValuedExpressionTimingStillLoads() {
        assertThat(loadInline(timed("\"= 10 / 2\"").trimIndent())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun aSphereWithAnAheadKeyIsALoadError() {
        val yaml = """
            apiVersion: astera/v3
            kind: Skill
            id: ahead
            machine:
              start: s
              states:
                s:
                  for: 4
                  do:
                    - {id: b, op: break_area, anchor: caster, shape: {sphere: {radius: 1.0, ahead: 4.0}}, tool: none, block_damage: 1, ripple: 0, revert: 10, outside_arena: false}
        """
        assertThat(loadError(yaml)).contains("ahead")
    }

    /** A projectile moving 5 blocks per tick; its `hit` sphere (radius 0.1) never contains the target at a tick boundary, only the swept segment does. */
    private val sweptHit = """
        apiVersion: astera/v3
        kind: Skill
        id: swept
        item: couture-rapier
        meta: {cooldown_ticks: 60, resource_cost: 0}
        anchors:
          - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
        machine:
          start: s
          states:
            s:
              for: 12
              do:
                - id: shot
                  op: projectile
                  at: 1
                  origin: muzzle
                  velocity: "= look(caster) * 5.0"
                  radius: 0.1
                  for: 6
                  hit: {targets: blocks_only, once: true, attach: false}
                  block: {mode: despawn}
                - id: probe
                  op: hit
                  at: 2
                  for: 3
                  every: 1
                  anchor: shot
                  shape: {sphere: 0.1}
                  targets: enemies
                  window: once
                  ignore_invulnerability: false
                  on:
                    hit:
                      - {id: landed, op: sound, sound: "astera:swept-hit", anchor: caster, at: 0}
    """

    @Test
    fun hitAnchoredOnAPerTickProjectileSweepsTheTrueOneTickSegment() {
        val skill: CompiledSkill = when (val r = loadInline(sweptHit.trimIndent())) {
            is Result.Success -> r.value
            is Result.Failure -> error("expected to load: ${r.error}")
        }
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, 8.0), 0.0, HitBox(Vec3(-0.3, 0.0, 7.7), Vec3(0.3, 1.8, 8.3)), 1.62))
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        player.hooks = rec.hooks
        rec.castItem = skill.def.item
        check(player.start(CastRequest(skill = skill, caster = rec.caster, world = WorldId("world"), casterFeet = Vec3(0.0, 0.0, 0.0), casterYawDeg = 0.0, casterPitchDeg = 0.0, seed = 1L, startTick = 0), skill) is Result.Success)
        for (now in 0..12) {
            rec.tick = now
            player.tick(now)
        }
        check(player.faults().isEmpty()) { "faulted: " + player.faults().joinToString("; ") { "${it.src}: ${it.message}" } }
        assertThat(rec.calls.count { it.verb == "sound" && it.payload.startsWith("sound=astera:swept-hit") }).isEqualTo(1)
    }
}
