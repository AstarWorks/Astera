package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Frame-grammar gaps G3-G7 and the snapshot frame (ADR-0314 follow-up): one scenario per new form, each through the real loader and
 * [com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer].
 */
class FrameGapsScenarioTest : SkillEngineScenario() {
    private fun compiled(yaml: String): CompiledSkill = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("expected to load: ${r.error}")
    }

    private fun failure(yaml: String): SkillDecodeError = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> throw AssertionError("expected a load error")
        is Result.Failure -> r.error
    }

    @Test
    fun aSnapshotFrameHoldsItsOriginAndDirectionsWhileALiveFrameFollowsTheCaster() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: snapshot-frame
            frames:
              - {id: live, origin: caster, v: forward, w: up, s: v x w}
              - {id: held, origin: caster, v: forward, w: up, s: v x w, snapshot: 2}
            machine:
              start: s
              states:
                s:
                  for: 8
                  then: end
                  do:
                    - {id: pl, op: particle, particle: flame, frame: live, pos: {v: 3.0}, count: 1, at: 0, every: 1, for: 8}
                    - {id: ph, op: particle, particle: crit, frame: held, pos: {v: 3.0}, count: 1, at: 0, every: 1, for: 8}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        play(skill, 1L, emptyList(), 9, rec, beforeTick = { t -> if (t >= 3) rec.casterYawDeg = 90.0 })
        val live = rec.emittedAt.filter { it.second == "flame" }.map { it.third }
        val held = rec.emittedAt.filter { it.second == "crit" }.map { it.third }
        assertThat(live.last()).describedAs("the live frame follows the turned caster").isNotEqualTo(live.first())
        assertThat(held.last()).describedAs("the snapshot frame stays where tick 2 left it").isEqualTo(held[3])
    }

    @Test
    fun aSnapshotFrameTypoIsALoudLoadError() {
        val e = failure(
            """
            apiVersion: astera/v3
            kind: Skill
            id: snapshot-typo
            frames:
              - {id: held, origin: caster, v: forward, w: up, s: v x w, snapshot: later}
            machine:
              start: s
              states:
                s: {for: 2, then: end}
                end: {final: true}
            """,
        )
        assertThat(e.toString()).describedAs(e.toString()).contains("held").contains("snapshot")
    }

    @Test
    fun aPartCanBeHandedAFrameInsteadOfAnAnchor() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: parry-on-frame
            frames:
              - {id: shield, origin: caster, v: forward, w: up, s: v x w, move: [{at: 0, for: 0, to: {v: 1.15, w: 1.3}}]}
            machine:
              start: guard
              states:
                guard:
                  use: parry
                  with: {window: 16, arc: 110, range: 4, recover: 4, whiff_lag: 1}
                  frames: {weapon: shield}
                  then: end
                end: {final: true}
            """,
        )
        assertThat(skill.def.id).isEqualTo("parry-on-frame")
    }
}
