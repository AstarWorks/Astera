package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `victim.center` (founder 2026-10-29): the point halfway between the victim's feet and its eye, read live every tick. A particle
 * anchored to it follows a walking victim at feet + eyeHeight / 2 (0.81 for an eye height of 1.62), and the height follows the
 * victim's own eye height (a smaller entity gets a lower point).
 */
class VictimCenterScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 11L)

    private fun emitted(eyeHeight: Double): List<Pair<Int, Vec3>> {
        val skill = when (
            val r = loadInline(
                """
                apiVersion: astera/v3
                kind: Skill
                id: victim-center
                machine:
                  start: s
                  states:
                    s:
                      for: 12
                      do:
                        - id: sel
                          op: hit
                          at: 0
                          anchor: caster
                          shape: {sphere: 8.0}
                          targets: enemies
                          ignore_invulnerability: false
                          damage: {physical: 1.0}
                          on:
                            hit:
                              - {id: mark, op: particle, particle: crit, anchor: victim.center, at: 1, every: 1, for: 6}
                """.trimIndent(),
            )
        ) {
            is Result.Success -> r.value
            is Result.Failure -> error("expected to load: ${r.error.message}")
        }
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        // the victim walks +x one block per tick from (0, 0, 2)
        rec.victimMotion = { t ->
            val x = t.toDouble()
            listOf(HitCandidate(foe, Vec3(x, 0.0, 2.0), 0.0, HitBox(Vec3(x - 0.3, 0.0, 1.7), Vec3(x + 0.3, 1.8, 2.3)), eyeHeight))
        }
        play(skill, seed = 1L, events = emptyList(), ticks = 12, rec = rec)
        return rec.emittedAt.filter { it.second == "crit" }.map { it.first to it.third }
    }

    @Test
    fun centerIsHalfwayFeetToEyeAndFollowsTheWalkingVictim() {
        val points = emitted(1.62)
        assertThat(points).hasSizeGreaterThanOrEqualTo(4)
        val tol = Offset.offset(1e-6)
        for ((tick, at) in points) {
            // the victim's feet at that tick are (tick, 0, 2): the point rides the walk and sits at half the eye height
            assertThat(at.y).isEqualTo(0.81, tol)
            assertThat(at.z).isEqualTo(2.0, tol)
            assertThat(at.x).describedAs("tick $tick").isEqualTo(tick.toDouble(), tol)
        }
        assertThat(points.map { it.second.x }.toSet().size).describedAs("the point moves with the victim").isEqualTo(points.size)
    }

    @Test
    fun centerHeightFollowsTheVictimsOwnEyeHeight() {
        assertThat(emitted(1.0).first().second.y).isEqualTo(0.5, Offset.offset(1e-6))
    }
}
