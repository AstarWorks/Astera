package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.grammar.FrameEvaluator
import com.astarworks.astera.core.domain.skillengine.frame.grammar.FrameInputs
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.DefaultCastRunner
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Frame grammar (ADR-0314) through the real [com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer]: a `frames:` entry is one origin and
 * three vectors; everything placed by `frame:` + `pos: {v, w, s}` reads the one per-tick frame table.
 *
 *  - the table is evaluated once per live frame per tick, however many rows read it;
 *  - a per-victim frame (`on: event.victim, each: true`) gives one instance per victim, each following its own victim;
 *  - world positions of keyed `move` / `rotate` tracks match the pure [FrameEvaluator] and the hand-computed value at a known key;
 *  - a coplanar basis is a decode error naming the field, never a silent fallback.
 */
class FrameGrammarScenarioTest : SkillEngineScenario() {
    private fun compiled(yaml: String): CompiledSkill = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("expected to load: ${r.error}")
    }

    private fun readers(skillId: String, perFrame: Int): String {
        val rows = (0 until perFrame).flatMap { i ->
            listOf(
                "                - {id: pa$i, op: particle, particle: flame, frame: a, pos: {v: 1.0, w: ${0.1 * i}}, count: 1, at: 0, every: 1, for: 10}",
                "                - {id: pb$i, op: particle, particle: flame, frame: b, pos: {s: 0.5}, count: 1, at: 0, every: 1, for: 10}",
            )
        }.joinToString("\n")
        return """
        apiVersion: astera/v3
        kind: Skill
        id: $skillId
        frames:
          - {id: a, origin: caster, v: forward, w: up, s: v x w, move: [{at: 0, for: 10, to: {v: 2.0}}]}
          - {id: b, origin: caster.eye, v: aim, w: up, s: v x w, rotate: [{at: 0, for: 10, about: s, to: 90}]}
        machine:
          start: s
          states:
            s:
              for: 10
              then: end
              do:
$rows
            end: {final: true}
        """
    }

    @Test
    fun theFrameTableIsEvaluatedOncePerTickWhateverTheReaderCount() {
        val one = compiled(readers("one-reader", 1))
        val many = compiled(readers("many-readers", 5))
        play(one, 1L, emptyList(), 12, GatewayRecorder())
        val evalOne = lastPlayer!!.frameEvaluations
        val recMany = GatewayRecorder()
        play(many, 1L, emptyList(), 12, recMany)
        val evalMany = lastPlayer!!.frameEvaluations
        println("FRAME-TABLE evaluations: 1 reader/frame=$evalOne, 5 readers/frame=$evalMany, particle calls (5 readers)=${recMany.calls.count { it.verb == "particle" }}")
        assertThat(recMany.calls.count { it.verb == "particle" }).isGreaterThanOrEqualTo(100)
        assertThat(evalMany).describedAs("one evaluation per frame per tick: readers do not add evaluations").isEqualTo(evalOne)
        assertThat(evalOne).describedAs("two frames, at most one evaluation each on the 13 played ticks").isBetween(2 * 10, 2 * 13)
    }

    private val a = UUID(0L, 31L)
    private val b = UUID(0L, 32L)
    private val c = UUID(0L, 33L)

    private fun candidate(id: UUID, x: Double, z: Double) =
        HitCandidate(id, Vec3(x, 0.0, z), 0.0, HitBox(Vec3(x - 0.3, 0.0, z - 0.3), Vec3(x + 0.3, 1.8, z + 0.3)), 1.62)

    @Test
    fun aPerVictimFrameGivesOneInstancePerVictimFollowingItsOwnVictim() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: three-victims
            frames:
              - {id: wound, on: event.victim, each: true, origin: victim.center, v: incoming, w: up, s: v x w}
            machine:
              start: s
              tail: 6
              states:
                s:
                  for: 10
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
                          - {id: burst, op: particle, particle: crit, frame: wound, pos: {v: -0.3}, count: 1, every: 1, times: 6, detach: true}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        // a walks along x, b walks along z, c stands still
        rec.victimMotion = { tick -> listOf(candidate(a, -2.0 + 0.3 * tick, 3.0), candidate(b, 0.0, 3.0 + 0.3 * tick), candidate(c, 2.0, 3.0)) }
        play(skill, 1L, emptyList(), 12, rec)
        val byTick = rec.emittedAt.filter { it.second == "crit" }.groupBy({ it.first }, { it.third })
        assertThat(byTick.keys.size).describedAs("ticks with impact emissions").isGreaterThanOrEqualTo(4)
        for ((tick, at) in byTick) {
            assertThat(at).describedAs("tick $tick: one emission per victim").hasSize(3)
            val centers = rec.victimMotion!!(tick).associate { it.entityId to it.center }
            val covered = HashSet<UUID>()
            for (p in at) {
                val (id, center) = centers.entries.minByOrNull { it.value.distance(p) }!!
                assertThat(center.distance(p)).describedAs("tick $tick: emission $p sits 0.3 from its victim's centre (pos v -0.3)").isBetween(0.29, 0.31)
                covered += id
            }
            assertThat(covered).describedAs("tick $tick: every victim has its own instance").containsExactlyInAnyOrder(a, b, c)
        }
        println("PER-VICTIM frames: " + byTick.entries.sortedBy { it.key }.joinToString { "t${it.key}=${it.value.size}" })
    }

    @Test
    fun keyedMoveAndRotateLandWhereTheEvaluatorAndTheHandSay() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: keyed-frame
            frames:
              - id: f
                origin: caster
                v: forward
                w: up
                s: v x w
                move: [{at: 0, for: 4, to: {v: 2.0, w: 1.0}}]
                rotate: [{at: 0, for: 4, about: s, to: 90}]
            machine:
              start: s
              states:
                s:
                  for: 8
                  then: end
                  do:
                    - {id: p, op: particle, particle: flame, frame: f, pos: {v: 1.0}, count: 1, at: 0, every: 1, for: 8}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 9, rec)
        val decl = skill.def.frames.single { it.id == "f" }
        val origin = Vec3(rec.casterPosition.x, rec.casterPosition.y, rec.casterPosition.z)
        val inputs = FrameInputs(origin) { name ->
            when (name) {
                "forward" -> Vec3(0.0, 0.0, 1.0)
                "up" -> Vec3(0.0, 1.0, 0.0)
                else -> null
            }
        }
        val emitted = rec.emittedAt.filter { it.second == "flame" }.associate { it.first to it.third }
        assertThat(emitted.keys).contains(0, 2, 4, 6)
        for ((tick, at) in emitted) {
            val expected = (FrameEvaluator.evaluate(decl, tick, inputs) as FrameEvaluator.Outcome.Ok).state.world(com.astarworks.astera.core.domain.skillengine.frame.grammar.Coeff(v = 1.0))
            assertThat(at.distance(expected)).describedAs("tick $tick: runtime vs FrameEvaluator").isLessThan(1e-6)
        }
        // by hand at the key's end (t = 4): the origin moved 2 along the DECLARED v (forward) and 1 along the declared w (up) - a move is the hand's
        // caster-relative path, untouched by the rotate keys (ADR-0315); v turned 90 about s = v x w onto w (0,1,0), and pos v 1.0 is one along the turned v
        val t4 = emitted.getValue(4)
        assertThat(t4.distance(origin + Vec3(0.0, 1.0, 2.0) + Vec3(0.0, 1.0, 0.0))).describedAs("t4 = origin + 2v + w + v' = origin + (0,2,2)").isLessThan(1e-6)
        // at t = 0 nothing has moved: pos v 1.0 is one block forward
        assertThat(emitted.getValue(0).distance(origin + Vec3(0.0, 0.0, 1.0))).isLessThan(1e-6)
    }

    @Test
    fun aCoplanarBasisIsADecodeErrorNamingTheField() {
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: flat-frame
            frames:
              - {id: f, origin: caster, v: up, w: up, s: v x w}
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - {id: p, op: particle, particle: flame, frame: f, pos: {v: 1.0}, count: 1, at: 0}
                end: {final: true}
            """.trimIndent(),
        )
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        val e = (r as Result.Failure<SkillDecodeError>).error
        assertThat(e.message).contains("frame 'f'").contains("coplanar")
        assertThat(e.path).contains("frames")
    }

    @Test
    fun aNamedLineSpreadsSamplesFromItsFromToItsTo() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: along-line
            frames:
              - {id: f, origin: caster, v: forward, w: up, s: v x w}
            lines:
              edge: {frame: f, from: {v: 0.5}, to: {v: 2.5}}
            machine:
              start: s
              states:
                s:
                  for: 4
                  then: end
                  do:
                    - {id: p, op: particle, particle: flame, along: edge, samples: 3, count: 1, at: 0, every: 1, for: 4}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 5, rec)
        val origin = Vec3(rec.casterPosition.x, rec.casterPosition.y, rec.casterPosition.z)
        val at0 = rec.emittedAt.filter { it.second == "flame" && it.first == 0 }.map { it.third }
        assertThat(at0).describedAs("samples: 3 on the line = three points, ends included").hasSize(3)
        for (k in listOf(0.5, 1.5, 2.5)) {
            assertThat(at0.minOf { it.distance(origin + Vec3(0.0, 0.0, k)) }).describedAs("a point at v $k of the line").isLessThan(1e-6)
        }
    }

    @Test
    fun aFrameDisplayIsOrientedByPlaneAndFrameAxesOnly() {
        fun display(orient: String) = """
            apiVersion: astera/v3
            kind: Skill
            id: plate
            frames:
              - {id: f, origin: caster, v: forward, w: up, s: v x w}
            machine:
              start: s
              states:
                s:
                  for: 4
                  then: end
                  do:
                    - {id: d, op: display, kind: sprite, sprite: bastion-s1-flare, size: [1.0, 1.0], frame: f, pos: {w: 1.5}, $orient, at: 0, for: 4}
                end: {final: true}
            """.trimIndent()
        assertThat(loadInline(display("plane: [-s, v]"))).isInstanceOf(Result.Success::class.java)
        assertThat(loadInline(display("rotate: [{axis: v, deg: 30}]"))).isInstanceOf(Result.Success::class.java)
        val named = loadInline(display("rotate: [{axis: left, deg: 90}]"))
        assertThat(named).isInstanceOf(Result.Failure::class.java)
        assertThat((named as Result.Failure<SkillDecodeError>).error.message).describedAs("caster-local axis names are not a frame display's vocabulary").contains("v, w or s")
        val flat = loadInline(display("plane: [v, v]"))
        assertThat(flat).isInstanceOf(Result.Failure::class.java)
        assertThat((flat as Result.Failure<SkillDecodeError>).error.path).contains("plane")
    }

    @Test
    fun aBlockFrameIsRootedOnTheContactAndNowhereElse() {
        fun frame(on: String) = """
            apiVersion: astera/v3
            kind: Skill
            id: block-frame
            anchors:
              - {id: start, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}
            frames:
              - {id: blk, ${on}origin: contact, v: incoming, w: up, s: v x w}
            machine:
              start: s
              tail: 4
              states:
                s:
                  for: 4
                  then: end
                  do:
                    - {id: b, op: projectile, origin: start, velocity: "= look(caster) * 2.0", for: 4, radius: 0.3, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}, on: {block: [{id: p, op: particle, particle: flame, frame: blk, pos: {v: -0.2}, count: 1, detach: true}]}}
                end: {final: true}
            """.trimIndent()
        assertThat(loadInline(frame("on: event.block, each: true, "))).isInstanceOf(Result.Success::class.java)
        val loose = loadInline(frame(""))
        assertThat(loose).isInstanceOf(Result.Failure::class.java)
        assertThat((loose as Result.Failure<SkillDecodeError>).error.message).describedAs("contact is only a block frame's origin").contains("event.block")
    }

    @Test
    fun anUndeclaredFrameIsACompileErrorNamingTheRow() {
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: no-such-frame
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - {id: p, op: particle, particle: flame, frame: ghost, pos: {v: 1.0}, count: 1, at: 0}
                end: {final: true}
            """.trimIndent(),
        )
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure<SkillDecodeError>).error.message).contains("ghost")
    }

    // ---- frame: + pos: on the gameplay / world / presentation ops (the same frame table as the anchor form) ----------------------

    private val frameF = "  - {id: f, origin: caster, v: forward, w: up, s: v x w}"

    private fun single(id: String, body: String, frames: Boolean = true): CompiledSkill {
        val head = (
            "apiVersion: astera/v3\nkind: Skill\nid: $id\n" + (if (frames) "frames:\n$frameF\n" else "") +
                "machine:\n  start: s\n  states:\n    s:\n      for: 6\n      then: end\n      do:\n"
            )
        return compiled(head + body + "\n    end: {final: true}\n")
    }

    private fun playRec(skill: CompiledSkill, setup: (GatewayRecorder) -> Unit = {}): GatewayRecorder {
        val rec = GatewayRecorder()
        setup(rec)
        play(skill, 1L, emptyList(), 8, rec)
        assertThat(lastPlayer!!.faults()).isEmpty()
        return rec
    }

    @Test
    fun explodeAndLightningAtAFramePointLandWhereTheAnchorFormLands() {
        val frameForm = playRec(single("boom-frame", """
                - {id: boom, op: explode, at: 0, frame: f, pos: {v: 2.0}, power: 2.0, break_blocks: false, fire: false}
                - {id: bolt, op: lightning, at: 0, frame: f, pos: {v: 2.0, w: 1.0}, visual_only: true}
        """.replaceIndent("                ")))
        val anchorForm = playRec(compiled(anchorVariant("boom-anchor")))
        assertThat(frameForm.world.explosions).hasSize(1)
        assertThat(frameForm.world.lightnings).hasSize(1)
        assertThat(frameForm.world.explosions.single().at).describedAs("explode at frame f pos v 2").isEqualTo(anchorForm.world.explosions.single().at)
        assertThat(frameForm.world.lightnings.single().at).describedAs("lightning at frame f pos v 2 w 1").isEqualTo(anchorForm.world.lightnings.single().at)
    }

    private fun anchorVariant(id: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: $id
        anchors:
          - {id: spot, anchor: caster, offset: {forward: 2.0}, mode: snapshot}
          - {id: spot_up, anchor: caster, offset: {forward: 2.0, up: 1.0}, mode: snapshot}
        machine:
          start: s
          states:
            s:
              for: 6
              then: end
              do:
                - {id: boom, op: explode, at: 0, anchor: spot, power: 2.0, break_blocks: false, fire: false}
                - {id: bolt, op: lightning, at: 0, anchor: spot_up, visual_only: true}
            end: {final: true}
    """.trimIndent()

    private val near = UUID(0L, 41L)
    private val far = UUID(0L, 42L)

    private fun two(rec: GatewayRecorder) { rec.victims = listOf(candidate(near, 0.0, 3.0), candidate(far, 0.0, -9.0)) }

    private fun damaged(rec: GatewayRecorder, verb: String = "damage") = rec.calls.filter { it.verb == verb }.map { it.instance }.toSet()

    @Test
    fun aHitOnARotatingFrameSweepsFromLastTicksPointAndCatchesAVictimBetweenTwoSamples() {
        // f turns 30 degrees a tick about s; the hit sits at v 3 (radius 0.2) and fires on tick 1 only. Its tick-0 point is (z 3, y 1) and its
        // tick-1 point is 30 degrees round the arc, well clear of the victim (z 3, box y 0..1.8). Only the sweep between the two reaches him.
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: hit-frame-sweep
            frames:
              - {id: f, origin: caster, v: forward, w: up, s: v x w, rotate: [{at: 0, for: 3, about: s, to: 90}]}
            machine:
              start: s
              states:
                s:
                  for: 6
                  then: end
                  do:
                    - id: cut
                      op: hit
                      at: 1
                      frame: f
                      pos: {v: 3.0}
                      shape: {sphere: 0.2}
                      targets: enemies
                      ignore_invulnerability: true
                      on:
                        hit:
                          - {id: dmg, op: damage, target: event.victim, damage: {physical: 3.0}}
                end: {final: true}
            """.trimIndent(),
        )
        val rec = playRec(skill) { it.victims = listOf(candidate(near, 0.0, 3.0)) }
        assertThat(damaged(rec)).describedAs("the victim between two tick samples of the turning frame").containsExactly(near.toString())
    }

    @Test
    fun hitAndAreaTargetsAtAFramePointPickTheSameVictimsAsTheAnchorForm() {
        val hitFrame = playRec(single("hit-frame", """
                - id: sel
                  op: hit
                  at: 1
                  frame: f
                  pos: {v: 1.0}
                  shape: {sphere: 4.0}
                  targets: enemies
                  ignore_invulnerability: true
                  on:
                    hit:
                      - {id: dmg, op: damage, target: event.victim, damage: {physical: 3.0}}
        """.replaceIndent("                ")), ::two)
        val hitAnchor = playRec(single("hit-anchor", """
                - id: sel
                  op: hit
                  at: 1
                  anchor: caster
                  shape: {sphere: 4.0}
                  targets: enemies
                  ignore_invulnerability: true
                  on:
                    hit:
                      - {id: dmg, op: damage, target: event.victim, damage: {physical: 3.0}}
        """.replaceIndent("                "), frames = false), ::two)
        assertThat(damaged(hitFrame)).describedAs("hit shape centred on frame f + v 1").containsExactly(near.toString())
        assertThat(damaged(hitFrame)).isEqualTo(damaged(hitAnchor))

        val areaFrame = playRec(single("area-frame", """
                - {id: dmg, op: damage, at: 1, target: {kind: enemies, frame: f, pos: {v: 3.0}, radius: 1.5}, damage: {physical: 2.0}}
                - {id: cure, op: status, at: 1, target: {kind: enemies, frame: f, pos: {v: 3.0}, radius: 1.5}, status: poison, stacks: 1, for: 20}
                - {id: heal, op: status, at: 1, target: {kind: allies, frame: f, pos: {v: -9.0}, radius: 1.5, include_self: false}, status: regen, stacks: 1, for: 20}
        """.replaceIndent("                ")), ::two)
        // the scripted query returns every victim whatever the volume, so the proof is the query volume the area asked for (centre + radius)
        fun queries(rec: GatewayRecorder) = rec.oracleRows.filter { it.verb == "hit" }.map { it.detail }

        val areaAnchor = playRec(compiled("""
            apiVersion: astera/v3
            kind: Skill
            id: area-anchor
            anchors:
              - {id: spot, anchor: caster, offset: {forward: 3.0}, mode: snapshot}
            machine:
              start: s
              states:
                s:
                  for: 6
                  then: end
                  do:
                    - {id: dmg, op: damage, at: 1, target: {kind: enemies, around: spot, radius: 1.5}, damage: {physical: 2.0}}
                    - {id: cure, op: status, at: 1, target: {kind: enemies, around: spot, radius: 1.5}, status: poison, stacks: 1, for: 20}
                end: {final: true}
        """), ::two)
        val frameQ = queries(areaFrame)
        val anchorQ = queries(areaAnchor)
        println("AREA-QUERIES frame=$frameQ anchor=$anchorQ")
        assertThat(anchorQ).hasSize(2)
        assertThat(frameQ.take(2)).describedAs("enemies areas at frame f + v 3 query the same centre and radius as the anchor form").isEqualTo(anchorQ)
        assertThat(frameQ).describedAs("the allies area sits at frame f - 9").hasSize(3)
        assertThat(frameQ[2]).isNotEqualTo(frameQ[0])
    }

    @Test
    fun pushDirectionsAlongAFrameAxisGiveTheWorldVectorsOfTheSameDirection() {
        fun impulses(body: String, frames: Boolean) = playRec(single("push-${if (frames) "frame" else "world"}", """
                - id: sel
                  op: hit
                  at: 1
                  anchor: caster
                  shape: {sphere: 8.0}
                  targets: enemies
                  ignore_invulnerability: true
                  on:
                    hit:
                      - $body
        """.replaceIndent("                "), frames = frames), ::two).calls.filter { it.verb == "effect" }.map { it.payload }
        val frameKnock = impulses("{id: kb, op: knockback, target: event.victim, frame: f, dir: {v: 1.0}, strength: 2.0}", true)
        val worldKnock = impulses("{id: kb, op: knockback, target: event.victim, direction: [0.0, 0.0, 1.0], strength: 2.0}", false)
        assertThat(frameKnock).describedAs("knockback along frame f v (caster forward = +z)").isNotEmpty().isEqualTo(worldKnock)
        val framePull = impulses("{id: pl, op: pull, target: event.victim, frame: f, dir: {v: -1.0}, distance: 1.5}", true)
        val worldPull = impulses("{id: pl, op: pull, target: event.victim, direction: [0.0, 0.0, -1.0], distance: 1.5}", false)
        assertThat(framePull).describedAs("pull along frame f -v").isNotEmpty().isEqualTo(worldPull)
        val frameImpulse = playRec(single("imp-frame", "                - {id: im, op: impulse, at: 1, target: caster, frame: f, dir: {v: 2.0, w: 1.0}}")).calls.filter { it.verb == "effect" }.map { it.payload }
        val worldImpulse = playRec(single("imp-world", "                - {id: im, op: impulse, at: 1, target: caster, vector: {x: 0.0, y: 1.0, z: 2.0}}", frames = false)).calls.filter { it.verb == "effect" }.map { it.payload }
        assertThat(frameImpulse).isEqualTo(worldImpulse)
    }

    @Test
    fun ribbonLineEndsAreFramePoints() {
        val skill = single("ribbon-frame", """
                - {id: rb, op: ribbon, at: 0, for: 4, from: {frame: f, pos: {v: 0.5}}, to: {frame: f, pos: {v: 3.0, w: 0.5}}, color: "#ffffff", thickness: 0.05}
        """.replaceIndent("                "))
        val rec = playRec(skill)
        assertThat(rec.calls.filter { it.verb == "spawn" }).describedAs("the ribbon is drawn").isNotEmpty()
    }

    @Test
    fun breakAreaAndPlaceShapeLoadAtAFramePoint() {
        compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: world-ops-frame
            frames:
            $frameF
            machine:
              start: s
              states:
                s:
                  for: 4
                  then: end
                  do:
                    - {id: brk, op: break_area, at: 0, frame: f, pos: {v: 2.0}, shape: {sphere: {radius: 1.0}}, tool: none, block_damage: 20, ripple: 29, revert: 200, outside_arena: false}
                    - {id: put, op: place_shape, at: 1, frame: f, pos: {v: 2.0}, shape: {bridge: {length: 3, width: 3}}, block: "astera:packed_clay", consume: none, ripple: 29, revert: 200, outside_arena: false}
                end: {final: true}
            """,
        )
    }

    @Test
    fun summonAtAFramePointStartsTheCalleeWhereTheAnchorFormStartsIt() {
        fun summoned(placement: String, decls: String): List<Vec3> {
            val yaml = """
                apiVersion: astera/v3
                kind: Skill
                id: turret
                frames_in: {spot: anchor}
                meta: {cooldown_ticks: 0, resource_cost: 0}
                machine:
                  start: watch
                  states:
                    watch:
                      for: 6
                      then: end
                      do:
                        - {id: pew, op: sound, sound: "astera:test-pew", anchor: spot, at: 1, volume: 0.5, pitch: 1.0}
                    end: {final: true}
                ---
                apiVersion: astera/v3
                kind: Skill
                id: totem
                meta: {cooldown_ticks: 0, resource_cost: 0}
                $decls
                machine:
                  start: place
                  states:
                    place:
                      for: 2
                      then: end
                      do:
                        - {id: spawn_turret, op: summon, at: 0, skill: turret, $placement, life: 20}
                    end: {final: true}
            """.trimIndent()
            val loaded = (decodeAll("<inline>", yaml) as? Result.Success)?.value ?: error("expected a clean decode: ${decodeAll("<inline>", yaml)}")
            val turret = (compileMain(loaded, "turret") as? Result.Success)?.value ?: error("turret did not compile")
            val totem = (compileMain(loaded, "totem") as? Result.Success)?.value ?: error("totem did not compile")
            val rec = GatewayRecorder()
            val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
            player.summonSkills = { id -> if (id == "turret") turret else null }
            val runner = DefaultCastRunner(player)
            runner.start(CastRequest(totem, rec.caster, WorldId("world"), Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0))
            for (t in 0..12) { rec.tick = t; runner.tick(t) }
            assertThat(player.faults()).isEmpty()
            return rec.soundPositions.map { it.second }
        }
        val viaFrame = summoned("frame: f, pos: {v: 2.0}", "frames:\n                  - {id: f, origin: caster.feet, v: forward, w: up, s: v x w}")
        val viaAnchor = summoned("anchor: plant", "anchors:\n                  - {id: plant, anchor: caster.feet, offset: {forward: 2}, mode: snapshot}")
        assertThat(viaFrame).describedAs("the summoned turret's sound sits at the frame point").hasSize(1)
        assertThat(viaFrame).isEqualTo(viaAnchor)
    }

    @Test
    fun anAnchorAndAFrameTogetherOrNeitherAreLoadErrorsAndAnUndeclaredFrameNamesItself() {
        fun load(place: String) = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: place-check
            frames:
            $frameF
            anchors:
              - {id: spot, anchor: caster, mode: snapshot}
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - {id: boom, op: explode, at: 0, power: 1.0, break_blocks: false, fire: false$place}
                end: {final: true}
            """.trimIndent(),
        )
        assertThat(load(", anchor: spot")).isInstanceOf(Result.Success::class.java)
        assertThat(load(", frame: f, pos: {v: 1.0}")).isInstanceOf(Result.Success::class.java)
        assertThat(load(", anchor: spot, frame: f")).describedAs("both").isInstanceOf(Result.Failure::class.java)
        assertThat(load("")).describedAs("neither").isInstanceOf(Result.Failure::class.java)
        val ghost = load(", frame: ghost, pos: {v: 1.0}")
        assertThat(ghost).isInstanceOf(Result.Failure::class.java)
        assertThat((ghost as Result.Failure<SkillDecodeError>).error.message).contains("ghost")
    }
}
