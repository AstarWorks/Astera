package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Paper-drop parity: `event.victim` is the hit event's own bound victim in the sim exactly as on Paper. Two victims hit by one `hit` op are
 * damaged once each (not once per victim per hit event), and asking for `event.victim` without a bound victim is a named error, never "every scripted victim".
 */
class EventVictimBindingScenarioTest : SkillEngineScenario() {
    private val a = UUID(0L, 21L)
    private val b = UUID(0L, 22L)

    private val skill = when (val r = loadInline(
        """
        apiVersion: astera/v3
        kind: Skill
        id: two-victims
        machine:
          start: s
          states:
            s:
              for: 6
              do:
                - id: sel
                  op: hit
                  at: 2
                  anchor: caster
                  shape: {sphere: 8.0}
                  targets: enemies
                  ignore_invulnerability: false
                  on:
                    hit:
                      - {id: dmg, op: damage, target: event.victim, damage: {physical: 1.0}}
        """.trimIndent(),
    )) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun candidate(id: UUID, x: Double) = HitCandidate(id, Vec3(x, 0.0, 3.0), 0.0, HitBox(Vec3(x - 0.3, 0.0, 2.7), Vec3(x + 0.3, 1.8, 3.3)), 1.62)

    @Test
    fun eachHitEventDamagesOnlyItsOwnVictim() {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(candidate(a, -1.0), candidate(b, 1.0))
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        player.hooks = rec.hooks
        rec.castItem = skill.def.item
        check(player.start(CastRequest(skill = skill, caster = rec.caster, world = WorldId("world"), casterFeet = Vec3(0.0, 0.0, 0.0), casterYawDeg = 0.0, casterPitchDeg = 0.0, seed = 1L, startTick = 0), skill) is Result.Success)
        for (now in 0..8) {
            rec.tick = now
            player.tick(now)
        }
        check(player.faults().isEmpty()) { player.faults().joinToString("; ") { it.message ?: "" } }
        assertThat(rec.calls.count { it.verb == "damage" }).describedAs("one damage per victim").isEqualTo(2)
    }

    @Test
    fun eventVictimWithoutABindingIsANamedError() {
        val rec = GatewayRecorder()
        rec.victims = listOf(candidate(a, 0.0))
        assertThatThrownBy { rec.live.targets(CastHandle(1L), TargetRule.Entity("event.victim"), null) }
            .hasMessageContaining("event.victim")
    }
}
