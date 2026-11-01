package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** Real YAML loading, compilation, hit selection and frame placement, including the shipped staff cast. */
class VerticalIncidenceScenarioTest : SkillEngineScenario() {
    private fun compiled(yaml: String) = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> error("load: ${r.error}")
    }

    private fun impact(fallback: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: vertical-hit
        frames:
          - {id: wound, on: event.victim, each: true, origin: victim.center, v: incoming, w: up, $fallback s: v x w}
        machine:
          start: strike
          states:
            strike:
              for: 3
              then: end
              do:
                - id: hit
                  op: hit
                  at: 1
                  anchor: caster
                  shape: {sphere: 12.0}
                  targets: enemies
                  ignore_invulnerability: true
                  on:
                    hit:
                      - {id: pose, op: display, kind: block, block: stone, frame: wound, pos: {v: 0.2}, for: 2}
            end: {final: true}
    """

    @Test
    fun hitsFromAllSixDirectionsProduceAPoseWithAnExplicitFallback() {
        for (feet in listOf(Vec3(0.0, -4.0, 0.0), Vec3(0.0, 4.0, 0.0),
            Vec3(-4.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0), Vec3(0.0, 0.0, -4.0), Vec3(0.0, 0.0, 4.0))) {
            for (fallback in listOf("w_fallback: [1, 0, 0],", "")) {
                val rec = GatewayRecorder().also {
                    it.faithfulPose = true
                    it.victims = listOf(HitCandidate(UUID(0, 77), feet, 0.0,
                        HitBox(feet + Vec3(-0.3, 0.0, -0.3), feet + Vec3(0.3, 1.8, 0.3)), 1.62))
                }
                val skill = compiled(impact(fallback))
                if (fallback.isEmpty() && feet.x == 0.0 && feet.z == 0.0) {
                    assertThatThrownBy { play(skill, 1L, emptyList(), 5, rec) }
                        .describedAs("no silent axis substitution").hasMessageContaining("coplanar")
                    continue
                }
                play(skill, 1L, emptyList(), 5, rec)
                assertThat(lastPlayer!!.faults()).describedAs("hit feet=$feet").isEmpty()
                // A stationary display receives its pose on spawn; it need not issue a later write.
                val display = rec.calls.single { it.verb == "spawn" && it.payload == "kind=block" }
                val center = rec.victims.single().center
                val expected = center + (center - Vec3(0.0, 1.62, 0.0)).normalize() * 0.2
                assertThat(display.detail).describedAs("hit spawn pose feet=$feet")
                    .contains("pos=${OracleFmt.v(expected)}", "rot=").doesNotContain("NaN")
            }
        }
    }

    @Test
    fun invalidFallbacksFailAtLoadWithTheFieldNamed() {
        for (fallback in listOf("[0, 0, 0]", "[0, 1, 0]", "right", "v x s")) {
            val loaded = loadInline(impact("w_fallback: $fallback,").trimIndent())
            assertThat(loaded).isInstanceOf(Result.Failure::class.java)
            assertThat((loaded as Result.Failure).error.toString()).contains("w_fallback")
        }
    }

    @Test
    fun shippedFireStaffEmitsOneCastCueAndSpawnsItsBoltAtVerticalAim() {
        val skill = compiled(contentDir.resolve("skills/flame-burst/skill.yaml").toFile().readText())
        for (pitch in listOf(-90.0, 0.0, 90.0)) {
            val rec = GatewayRecorder().also { it.faithfulPose = true }
            play(skill, 1L, emptyList(), 8, rec, pitchDeg = pitch)
            assertThat(lastPlayer!!.faults()).describedAs("staff pitch=$pitch").isEmpty()
            assertThat(rec.calls.filter { it.verb == "sound" && "sound=astera:skill/wand/cast " in it.payload }).hasSize(1)
            assertThat(rec.writtenPoses).describedAs("staff bolt shell pitch=$pitch").isNotEmpty()
        }
    }
}
