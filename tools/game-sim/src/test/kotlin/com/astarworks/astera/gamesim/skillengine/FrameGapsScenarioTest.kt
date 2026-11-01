package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
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
    fun aPosExpressionOnADisplayRowSpacesTheFamilyAlongTheAxis() {
        val skill = compiled(exprSkill("pos-expr-display", "{id: d, op: display, kind: text, text: hi, frame: line, pos: {v: \"= i * 0.5\"}, times: 3, for: 4}"))
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 6, rec)
        val o = rec.calls.filter { it.verb == "spawn" && it.payload == "kind=text" }.map { c ->
            val (x, _, z) = Regex("pos=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)").find(c.detail)!!.destructured.toList().map { it.toDouble() }
            Pair(x, z)
        }
        assertThat(o).hasSize(3)
        val d1 = Math.hypot(o[1].first - o[0].first, o[1].second - o[0].second)
        val d2 = Math.hypot(o[2].first - o[0].first, o[2].second - o[0].second)
        assertThat(d1).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(d2).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun aPosExpressionOnALightningRowBakesAtItsSpawnTick() {
        val skill = compiled(exprSkill("pos-expr-bolt", "{id: bolt, op: lightning, at: 0, frame: line, pos: {v: \"= i * 0.5\"}, visual_only: true, times: 3}"))
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 6, rec)
        val at = rec.world.lightnings.map { it.at }
        assertThat(at).hasSize(3)
        val d = at.map { Math.hypot(it.x - at[0].x, it.z - at[0].z) }.sorted()
        assertThat(d[1]).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(d[2]).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6))
    }

    @Test
    fun anArmFrameOnTheBodyAxesFollowsTheTorsoWithNoKeys() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: arm-on-torso
            frames:
              - {id: arm, origin: caster.shoulder_r, v: body.forward, w: body.up, s: v x w}
            machine:
              start: s
              world:
                - {id: body_anim, op: body, target: caster, anim: bastion-s1-body, speed: 1.0, for: 12}
              states:
                s:
                  for: 12
                  enter:
                    - {id: shoulder, op: particle, particle: flame, frame: arm, pos: {}, at: 0, every: 1, for: 12}
                    - {id: hand, op: particle, particle: crit, frame: arm, pos: {v: 0.6}, at: 0, every: 1, for: 12}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 12, rec)
        val shoulder = rec.emittedAt.filter { it.second == "flame" }.associate { it.first to it.third }
        val hand = rec.emittedAt.filter { it.second == "crit" }.associate { it.first to it.third }
        val pushed = rec.pushedBody.associateBy { it.tick }
        var turned = 0.0
        var judged = 0
        for ((tick, s) in shoulder) {
            val h = hand[tick] ?: continue
            val torso = SkinRigTransforms.spine((pushed[tick] ?: continue).frame, false).rotation
            // the torso turns the caster's forward axis (0, 0, 1); a caster at yaw 0 reads it in world axes
            val fx = 2 * (torso.x * torso.z + torso.w * torso.y)
            val fy = 2 * (torso.y * torso.z - torso.w * torso.x)
            val fz = 1 - 2 * (torso.x * torso.x + torso.y * torso.y)
            assertThat(h.x - s.x).describedAs("t$tick shoulder->hand x in body space").isCloseTo(0.6 * fx, org.assertj.core.data.Offset.offset(1e-4))
            assertThat(h.y - s.y).describedAs("t$tick shoulder->hand y in body space").isCloseTo(0.6 * fy, org.assertj.core.data.Offset.offset(1e-4))
            assertThat(h.z - s.z).describedAs("t$tick shoulder->hand z in body space").isCloseTo(0.6 * fz, org.assertj.core.data.Offset.offset(1e-4))
            turned = maxOf(turned, Math.hypot(fx, fy))
            judged++
        }
        assertThat(judged).describedAs("ticks judged").isGreaterThan(6)
        assertThat(turned).describedAs("the clip really turns the torso (otherwise the check proves nothing)").isGreaterThan(0.05)
    }

    @Test
    fun aMisspeltBodyAxisIsALoudLoadErrorNamingIt() {
        val e = failure(
            """
            apiVersion: astera/v3
            kind: Skill
            id: arm-typo
            frames:
              - {id: arm, origin: caster.shoulder_r, v: body.forwrd, w: up, s: v x w}
            machine:
              start: s
              states:
                s: {for: 2, then: end}
                end: {final: true}
            """,
        )
        assertThat(e.toString()).describedAs(e.toString()).contains("body.forwrd")
    }

    private val victimA = java.util.UUID(0L, 41L)
    private val victimB = java.util.UUID(0L, 42L)

    private fun candidate(id: java.util.UUID, x: Double, z: Double) =
        HitCandidate(id, Vec3(x, 0.0, z), 0.0, HitBox(Vec3(x - 0.3, 0.0, z - 0.3), Vec3(x + 0.3, 1.8, z + 0.3)), 1.62)

    @Test
    fun anOpLevelSkillCallCanBindAnEventFrameOfTheCaller() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: shard
            frames:
              - {id: hit, on: event.victim, each: true, origin: victim.center, v: incoming, w: up, s: v x w}
            machine:
              start: s
              states:
                s:
                  for: 6
                  then: end
                  do:
                    - id: sel
                      op: hit
                      at: 2
                      anchor: caster
                      shape: {sphere: 8.0}
                      targets: enemies
                      ignore_invulnerability: true
                      on:
                        hit:
                          - {id: burst, use: skill:shard-burst, with: {snow: 3, rod: 1, dust: 1}, frames: {spot: hit}}
                end: {final: true}
            ---
            apiVersion: astera/v3
            kind: Skill
            id: shard-burst
            meta: {cooldown_ticks: 0, resource_cost: 0}
            frames_in: {spot: anchor}
            params:
              snow: {type: number, required: true}
              rod:  {type: number, required: true}
              dust: {type: number, required: true}
            machine:
              start: s
              states:
                s:
                  for: 1
                  then: e
                  enter:
                    - {id: fx_snow, op: particle, particle: snowflake, anchor: spot, count: "${'$'}{snow}", extra: 0.15, at: 0}
                    - {id: fx_rod, op: particle, particle: snowflake, anchor: spot, count: "${'$'}{rod}", extra: 0.2, at: 0}
                    - {id: fx_dust, op: particle, particle: dust, anchor: spot, count: "${'$'}{dust}", offset: [0.4, 0.4, 0.4], color: "#bee6ff", size: 1.4, at: 0}
                e: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        rec.victimMotion = { _ -> listOf(candidate(victimA, -2.0, 3.0), candidate(victimB, 2.0, 3.0)) }
        play(skill, 1L, emptyList(), 10, rec)
        val centres = rec.victimMotion!!(0).map { it.center }
        val burst = rec.emittedAt.filter { it.second == "snowflake" || it.second == "dust" }
        assertThat(burst).describedAs("burst rows played").isNotEmpty()
        val covered = HashSet<Int>()
        for ((tick, name, at) in burst) {
            val k = centres.indexOfFirst { it.distance(at) < 1e-6 }
            assertThat(k).describedAs("t$tick $name at $at sits on a victim's hit-frame origin").isGreaterThanOrEqualTo(0)
            covered += k
        }
        assertThat(covered).describedAs("each victim has its own burst").containsExactlyInAnyOrder(0, 1)
    }
}
