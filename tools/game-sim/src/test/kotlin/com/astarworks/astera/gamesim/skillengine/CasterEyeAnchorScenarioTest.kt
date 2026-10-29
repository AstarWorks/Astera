package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import org.junit.jupiter.api.Test

/**
 * `pos(caster.eye)` is the caster's live eye position (the same point `anchor: caster.eye` names), so content never writes an eye height
 * literal; an unknown point of `caster` / `victim` in `pos()` / `point()` is a load error.
 */
class CasterEyeAnchorScenarioTest : SkillEngineScenario() {
    private fun skill(offsetUp: String, expr: String = "pos(caster.eye)"): String =
        """
        apiVersion: astera/v3
        kind: Skill
        id: eye-anchor
        machine:
          start: s
          states:
            s:
              for: 6
              do:
                - {id: mark, op: particle, particle: crit, anchor: {of: caster, offset: {up: "= $offsetUp"}}, at: 1}
        """.trimIndent().replace("pos(caster.eye)", expr)

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> error("expected a load error")
        is Result.Failure -> r.error.message
    }

    @Test
    fun eyeAnchorIsTheLiveEyeHeightAboveTheFeet() {
        val s = when (val r = loadInline(skill("dist(pos(caster.eye), pos(caster))"))) {
            is Result.Success -> r.value
            is Result.Failure -> error("expected to load: ${r.error.message}")
        }
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.casterPosition = Vector3d(0.0, 5.0, 0.0)
        play(s, seed = 1L, events = emptyList(), ticks = 6, rec = rec)
        val at = rec.emittedAt.first { it.second == "crit" }.third
        // feet are y 4 (the recorder's caster stands 1.0 above them); the eye is 1.62 above the feet
        assertThat(at.y).isEqualTo(4.0 + 1.62, Offset.offset(1e-6))
    }

    @Test
    fun unknownCasterPointInPosIsALoadError() {
        assertThat(failure(skill("dist(pos(caster.bogus), pos(caster))"))).contains("not one of them")
        assertThat(failure(skill("dist(pos(point(caster, 'bogus')), pos(caster))"))).contains("bogus").contains("eye")
    }
}
