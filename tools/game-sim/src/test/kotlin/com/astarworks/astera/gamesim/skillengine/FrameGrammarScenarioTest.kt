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
        // by hand at the key's end (t = 4): v turned 90 about s = v x w onto w (0,1,0), w onto -forward; the origin moved 2 along the turned v and 1 along the turned w,
        // and pos v 1.0 is one more along the turned v
        val t4 = emitted.getValue(4)
        assertThat(t4.distance(origin + Vec3(0.0, 2.0, -1.0) + Vec3(0.0, 1.0, 0.0))).describedAs("t4 = origin + 2v' + w' + v' = origin + (0,3,-1)").isLessThan(1e-6)
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
}
