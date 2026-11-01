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
        assertThat(e.toString()).describedAs(e.toString()).contains("frames[0].snapshot").contains("whole tick")
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

    @Test
    fun anItemPartRidesAFrameAndTurnsAboutItsOwnEdge() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: item-part-frame
            item: bastion-greatsword
            frames:
              - {id: sword, origin: caster, v: forward, w: up, s: v x w}
            machine:
              start: s
              states:
                s:
                  for: 4
                  then: end
                  enter:
                    - {id: blade, op: display, kind: item, item: "astera:bastion-greatsword", part: held, frame: sword, pos: {s: 0.5}, rotate: [{axis: edge, deg: 35}], for: 4}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 5, rec)
        assertThat(rec.calls.filter { it.verb == "spawn" && it.payload == "kind=item" }).hasSize(1)
    }

    @Test
    fun anUnknownItemAxisOnAFrameDisplayIsALoudLoadError() {
        val e = failure(
            """
            apiVersion: astera/v3
            kind: Skill
            id: item-axis-typo
            item: bastion-greatsword
            frames:
              - {id: sword, origin: caster, v: forward, w: up, s: v x w}
            machine:
              start: s
              states:
                s:
                  for: 4
                  then: end
                  enter:
                    - {id: blade, op: display, kind: item, item: "astera:bastion-greatsword", part: held, frame: sword, rotate: [{axis: egde, deg: 35}], for: 4}
                end: {final: true}
            """,
        )
        assertThat(e.toString()).describedAs(e.toString()).contains("egde")
    }

    @Test
    fun aTrailAndAThreadRunBetweenFramePoints() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: ribbon-frame-points
            frames:
              - {id: piece, origin: caster, v: forward, w: up, s: v x w, move: [{at: 0, for: 6, to: {v: 6}}]}
              - {id: palm, origin: caster, v: forward, w: up, s: v x w}
            machine:
              start: s
              states:
                s:
                  for: 8
                  then: end
                  enter:
                    - {id: trail, op: ribbon, of: {frame: piece, pos: {v: 1}}, root: {frame: piece, pos: {v: 0}}, history: 6, style: soft, width_frac: 0.6, fade: 1.5, color: "#59ff5fa2", at: 0, for: 7}
                    - {id: thread, op: ribbon, from: {frame: palm, pos: {}}, to: {frame: piece, pos: {w: 1}}, thickness: 0.05, color: "#ff5fa2", at: 0, for: 7}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 8, rec)
        val trail = rec.oracleRows.filter { it.verb == "trail" }.map { it.detail }
        assertThat(trail).describedAs("one trail point per tick").hasSizeGreaterThan(4)
        assertThat(trail.first()).describedAs("root and tip are two different frame points").isNotEqualTo(trail.last())
        val thread = rec.oracleRows.first { it.verb == "spawn" && it.detail.startsWith("from=") }.detail
        assertThat(thread).contains("from=").contains("to=")
    }

    @Test
    fun aFrameCanSitOnAnAnimatedBodyHand() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: frame-on-hand
            anchors:
              - {id: right_hand, anchor: body_anim.hand_r, mode: follow}
            frames:
              - {id: palm, origin: body_anim.hand_r, v: forward, w: up, s: v x w}
            machine:
              start: s
              world:
                - {id: body_anim, op: body, target: caster, anim: bastion-s1-body, speed: 1.0, for: 12}
              states:
                s:
                  for: 12
                  enter:
                    - {id: fp, op: particle, particle: flame, frame: palm, pos: {}, at: 0, every: 4, for: 9}
                    - {id: ap, op: particle, particle: crit, anchor: right_hand, at: 0, every: 4, for: 9}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 12, rec)
        val viaFrame = rec.emittedAt.filter { it.second == "flame" }.map { it.third }
        val viaAnchor = rec.emittedAt.filter { it.second == "crit" }.map { it.third }
        assertThat(viaFrame).hasSize(3)
        assertThat(viaFrame.first()).describedAs("the hand moves with the clip").isNotEqualTo(viaFrame.last())
        assertThat(viaFrame).describedAs("a frame on the hand reads the same point as the anchor").isEqualTo(viaAnchor)
    }

    @Test
    fun aProjectileBodyFrameSpinsAboutItsOwnLongAxis() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: projectile-spin
            anchors:
              - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
            frames:
              - {id: body, origin: ball, v: up, w: forward, s: v x w, rotate: [{at: 4, for: 7, about: v, from: 0, to: 378}]}
            machine:
              start: s
              states:
                s:
                  for: 14
                  then: end
                  do:
                    - {id: mark, op: particle, particle: flame, frame: body, pos: {w: 1.0}, count: 1, at: 0, every: 1, until: ball.end}
                    - {id: centre, op: particle, particle: crit, frame: body, pos: {}, count: 1, at: 0, every: 1, until: ball.end}
                    - id: ball
                      op: projectile
                      at: 0
                      origin: muzzle
                      velocity: "= look(caster)"
                      speed: 0.5
                      range: 30
                      for: 14
                      radius: 0.3
                      hit: {targets: enemies, once: true, attach: false}
                      block: {mode: despawn}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 14, rec)
        fun offset(tick: Int): List<Double> {
            val m = rec.emittedAt.first { it.first == tick && it.second == "flame" }.third
            val c = rec.emittedAt.first { it.first == tick && it.second == "crit" }.third
            return listOf(m.x - c.x, m.y - c.y, m.z - c.z)
        }
        assertThat(offset(3)).describedAs("before the spin").isNotEqualTo(offset(6))
        assertThat(offset(6)).describedAs("the spin keeps the radius").satisfies({ o -> assertThat(Math.sqrt(o.sumOf { it * it })).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6)) })
    }

    private fun exprSkill(id: String, op: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: $id
        frames:
          - {id: line, origin: caster, v: forward, w: up, s: v x w}
        machine:
          start: s
          states:
            s:
              for: 6
              then: end
              do:
                - $op
            end: {final: true}
    """

    @Test
    fun aPosCoefficientCanBeACelExpressionOfTheOccurrenceIndex() {
        val skill = compiled(exprSkill("pos-expr", "{id: spark, op: particle, particle: flame, frame: line, pos: {v: \"= i * 0.5\", w: \"= 1.0 + t * 0.0\"}, count: 1, at: 0, every: 1, for: 4}"))
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 6, rec)
        val at = rec.emittedAt.filter { it.second == "flame" }.sortedBy { it.first }.map { it.third }
        assertThat(at).hasSize(4)
        val d = (1 until at.size).map { Math.hypot(at[it].x - at[0].x, at[it].z - at[0].z) }
        assertThat(d[0]).describedAs("occurrence 1 sits 0.5 along v from occurrence 0").isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(d[2]).describedAs("occurrence 3 sits 1.5 along v").isCloseTo(1.5, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun aBadPosExpressionIsALoudLoadErrorNamingThePath() {
        val e = failure(exprSkill("pos-expr-bad", "{id: spark, op: particle, particle: flame, frame: line, pos: {v: \"= i * \"}, count: 1, at: 0, for: 2}"))
        assertThat(e.toString()).describedAs(e.toString()).contains("pos.v")
    }

    @Test
    fun aPosExpressionOnAnOpThatTakesNumbersOnlyIsALoudLoadError() {
        val e = failure(exprSkill("pos-expr-display", "{id: d, op: display, kind: text, text: hi, frame: line, pos: {v: \"= i\"}, for: 3}"))
        assertThat(e.toString()).describedAs(e.toString()).contains("pos")
    }
}
