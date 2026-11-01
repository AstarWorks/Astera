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
    fun aProjectileBodyIsAFrameOriginThatTravelsWithTheBullet() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: bolt-frame
            anchors:
              - {id: start, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}
            frames:
              - {id: shot, origin: bolt, v: velocity, w: up, s: v x w}
            lines:
              tail: {frame: shot, from: {}, to: {v: -0.4}}
            machine:
              start: s
              states:
                s:
                  for: 6
                  then: end
                  do:
                    - {id: bolt, op: projectile, origin: start, velocity: "= look(caster) * 2.0", for: 6, radius: 0.3, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}}
                    - {id: trail, op: particle, particle: flame, frame: shot, pos: {v: -0.4}, count: 1, at: 0, every: 1, for: 6}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 9, rec)
        val emitted = rec.emittedAt.filter { it.second == "flame" }.sortedBy { it.first }
        assertThat(emitted.map { it.first }).describedAs("a presentation row at the bolt's spawn tick reads its frame, which then follows it every tick").containsExactly(0, 1, 2, 3, 4, 5)
        assertThat(emitted.first().third.z).describedAs("at the spawn tick the frame sits at the muzzle (0.5 ahead), the point 0.4 behind it").isBetween(0.09, 0.11)
        for ((a, b) in emitted.drop(1).zipWithNext()) assertThat(a.third.distance(b.third)).describedAs("the trail point rides the bullet: 2 blocks a tick once it flies").isBetween(1.99, 2.01)
    }

    @Test
    fun aSummonBodyIsAFrameOriginAtItsSpawnPoint() {
        val text = """
            apiVersion: astera/v3
            kind: Skill
            id: turret
            frames_in: {spot: anchor}
            meta: {cooldown_ticks: 0, resource_cost: 0}
            machine:
              start: watch
              states:
                watch:
                  for: 10
                  then: end
                  do: []
                end: {final: true}
            ---
            apiVersion: astera/v3
            kind: Skill
            id: totem
            meta: {cooldown_ticks: 0, resource_cost: 0}
            anchors:
              - {id: plant, anchor: caster.feet, offset: {forward: 2}, mode: snapshot}
            frames:
              - {id: post, origin: sentry, v: forward, w: up, s: v x w}
            machine:
              start: place
              states:
                place:
                  for: 5
                  then: end
                  do:
                    - {id: sentry, op: summon, at: 0, skill: turret, anchor: plant, life: 10}
                    - {id: mark, op: particle, particle: flame, frame: post, pos: {v: 1.0, w: 0.5}, count: 1, at: 0, every: 1, for: 4}
                end: {final: true}
            """.trimIndent()
        val docs = (decodeAll("<inline>", text) as? Result.Success)?.value ?: error("expected a clean decode")
        val turret = (compileMain(docs, "turret") as? Result.Success)?.value ?: error("turret did not compile")
        val totem = (compileMain(docs, "totem") as? Result.Success)?.value ?: error("totem did not compile")
        summonSkills = { if (it == "turret") turret else null }
        val rec = GatewayRecorder()
        play(totem, 1L, emptyList(), 7, rec)
        val marks = rec.emittedAt.filter { it.second == "flame" }.sortedBy { it.first }
        assertThat(marks.map { it.first }).describedAs("a presentation row at the spawn tick reads the frame: the compiler orders it after the summon that roots it").containsExactly(0, 1, 2, 3)
        for (m in marks) {
            assertThat(m.third.x).isBetween(-0.01, 0.01)
            assertThat(m.third.z).describedAs("the turret stands 2 ahead, the mark 1 beyond it").isBetween(2.99, 3.01)
            // The recorder answers caster.feet at casterPosition (GatewayRecorder.kt:645, default y 1.0); the w axis adds 0.5 above the spawn point.
            assertThat(m.third.y).describedAs("spawn point y plus w 0.5").isBetween(rec.casterPosition.y + 0.49, rec.casterPosition.y + 0.51)
        }
    }

    @Test
    fun aLineWhoseEndsSitOnTwoFramesFollowsBoth() {
        val skill = compiled(
            """
            apiVersion: astera/v3
            kind: Skill
            id: beam
            frames:
              - {id: a, origin: caster, v: forward, w: up, s: v x w}
              - id: b
                origin: caster
                v: forward
                w: up
                s: v x w
                move:
                  - {at: 0, for: 4, to: {v: 4.0}, ease: linear}
            lines:
              span: {from: {frame: a, pos: {v: 1.0}}, to: {frame: b, pos: {v: 1.0}}}
            machine:
              start: s
              states:
                s:
                  for: 6
                  then: end
                  do:
                    - {id: beam, op: particle, particle: flame, along: span, samples: 3, count: 1, at: 4}
                end: {final: true}
            """,
        )
        val rec = GatewayRecorder()
        play(skill, 1L, emptyList(), 7, rec)
        val pts = rec.emittedAt.filter { it.second == "flame" && it.first == 4 }.map { it.third }.sortedBy { it.z }
        assertThat(pts).describedAs("three samples from frame a's point to frame b's point, the far end 4 further along").hasSize(3)
        assertThat(pts.map { it.z }).satisfies({ z -> assertThat(z[0]).isBetween(0.99, 1.01); assertThat(z[1]).isBetween(2.99, 3.01); assertThat(z[2]).isBetween(4.99, 5.01) })
    }

    @Test
    fun aFrameRootedOnAnIdNoOpSpawnsIsALoadErrorNamingFrameAndId() {
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: typo-origin
            anchors:
              - {id: start, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}
            frames:
              - {id: head, origin: bolt, v: velocity, w: up, s: v x w}
            machine:
              start: s
              states:
                s:
                  for: 6
                  then: end
                  do:
                    - {id: blot, op: projectile, origin: start, velocity: "= look(caster) * 2.0", for: 6, radius: 0.3, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}}
                    - {id: mark, op: particle, particle: flame, frame: head, count: 1, at: 1}
                end: {final: true}
            """.trimIndent(),
        )
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        val msg = (r as Result.Failure<SkillDecodeError>).error.toString()
        assertThat(msg).describedAs("names the frame and the instance id").contains("head").contains("bolt")
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
        val exprKnock = impulses("{id: kb, op: knockback, target: event.victim, frame: f, dir: {v: \"= 0.5 + 0.5\"}, strength: 2.0}", true)
        assertThat(exprKnock).describedAs("a dir coefficient may be an '= expr' like pos: (here 1.0)").isEqualTo(frameKnock)
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

    @Test
    fun aFramePointOriginStaysItsReachFromTheParentOnEveryTickUnderARotatingParent() {
        fun skill(blade: String) = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: arm-reach
            frames:
              - id: arm
                origin: caster.shoulder_r
                v: forward
                w: up
                s: v x w
                rotate:
                  - {at: 0, for: 4, about: w, to: -70, ease: in_out_sine}
                  - {at: 4, for: 4, about: s, to: 80, ease: out_quad}
                  - {at: 8, for: 2, about: w, to: 30}
$blade
            machine:
              start: s
              states:
                s:
                  for: 10
                  then: end
                  do:
                    - {id: a, op: particle, particle: flame, frame: arm, count: 1, at: 0, every: 1, for: 10}
                    - {id: b, op: particle, particle: smoke, frame: blade, count: 1, at: 0, every: 1, for: 10}
                end: {final: true}
            """.trimIndent(),
        )
        val ok = skill("              - {id: blade, origin: {frame: arm, pos: {v: 0.6}}, v: forward, w: up, s: v x w, rotate: [{at: 2, for: 5, about: s, to: 60}]}")
        val compiledOk = (ok as? Result.Success)?.value ?: error("expected to load: $ok")
        val rec = GatewayRecorder()
        play(compiledOk, 1L, emptyList(), 12, rec)
        assertThat(lastPlayer!!.faults()).isEmpty()
        val arm = rec.emittedAt.filter { it.second == "flame" }.associate { it.first to it.third }
        val blade = rec.emittedAt.filter { it.second == "smoke" }.associate { it.first to it.third }
        assertThat(blade.keys).describedAs("the child is drawn every tick").containsExactlyInAnyOrderElementsOf(arm.keys).hasSize(10)
        for (t in arm.keys) assertThat(blade.getValue(t).distance(arm.getValue(t))).describedAs("tick $t: the child's origin stays 0.6 from the parent's").isBetween(0.5999, 0.6001)
        assertThat(arm.getValue(0).distance(arm.getValue(9))).describedAs("the parent's origin is the shoulder, not moving with its own rotation").isLessThan(1e-6)
        assertThat(blade.getValue(0).distance(blade.getValue(9))).describedAs("the child swings round the shoulder").isGreaterThan(0.3)

        val loop = skill("              - {id: blade, origin: {frame: kid, pos: {v: 0.6}}, v: forward, w: up, s: v x w}\n              - {id: kid, origin: {frame: blade, pos: {v: 0.1}}, v: forward, w: up, s: v x w}")
        assertThat(loop).describedAs("a loop of frame origins").isInstanceOf(Result.Failure::class.java)
        assertThat((loop as Result.Failure<SkillDecodeError>).error.message).contains("cycle")
        val ghost = skill("              - {id: blade, origin: {frame: nowhere, pos: {v: 0.6}}, v: forward, w: up, s: v x w}")
        assertThat(ghost).describedAs("a parent not under frames:").isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun anAimOriginIsTheAimPointExpressionAndAFrameInNameIsReadByTheCallee() {
        fun emitted(body: String, frames: String) = GatewayRecorder().also { rec ->
            play(compiled("""
            apiVersion: astera/v3
            kind: Skill
            id: aim-origin
            frames:
$frames
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - $body
                end: {final: true}
            """), 1L, emptyList(), 4, rec)
        }.emittedAt.filter { it.second == "flame" }.map { it.third }
        val viaFrame = emitted("{id: p, op: particle, particle: flame, frame: aimed, count: 1, at: 0}", "              - {id: aimed, origin: {aim: 6.0}, v: aim, w: up, s: v x w}")
        val viaExpr = emitted("{id: p, op: particle, particle: flame, frame: eye, pos: {v: \"= dist(pos(caster.eye), aim_point(caster, 6.0))\"}, count: 1, at: 0}", "              - {id: eye, origin: caster.eye, v: aim, w: up, s: v x w}")
        assertThat(viaFrame).hasSize(1)
        assertThat(viaFrame[0].distance(viaExpr[0])).describedAs("origin {aim: 6} is the point aim_point(caster, 6) names").isLessThan(1e-6)
        val ranged = emitted("{id: p, op: particle, particle: flame, frame: aimed, pos: {v: 1.0}, count: 1, at: 0}", "              - {id: aimed, origin: {aim: \"= 2.0 * 3.0\"}, v: aim, w: up, s: v x w}")
        assertThat(ranged[0].distance(viaFrame[0])).describedAs("the range may be an '= expr'; pos is along the eye's aim axis").isBetween(0.9999, 1.0001)
    }

    @Test
    fun aParamInAFrameOriginAndInFrameCoefficientsIsReadFromTheSkillsParams() {
        fun emitted(params: String, frames: String, pos: String) = GatewayRecorder().also { rec ->
            play(compiled("""
            apiVersion: astera/v3
            kind: Skill
            id: param-origin
            params:
$params
            frames:
$frames
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - {id: p, op: particle, particle: flame, frame: aimed, pos: $pos, count: 1, at: 0}
                end: {final: true}
            """), 1L, emptyList(), 4, rec)
        }.emittedAt.filter { it.second == "flame" }.map { it.third }
        val literal = emitted("              ahead: {type: number, default: 6.0}", "              - {id: aimed, origin: {aim: 6.0}, v: aim, w: up, s: v x w}", "{v: 1.0}")
        val viaParam = emitted("              ahead: {type: number, default: 6.0}", "              - {id: aimed, origin: {aim: \"\${ahead}\"}, v: aim, w: up, s: v x w}", "{v: \"\${ahead}\"}")
        assertThat(viaParam).hasSize(1)
        assertThat(literal[0].distance(viaParam[0])).describedAs("{aim: \${p}} = 6 and pos v \${p} = 6 move the point 5 further along aim").isBetween(4.9999, 5.0001)
    }

    @Test
    fun theBuiltInCasterEyeFrameHasVolumeForEveryLookDirectionIncludingStraightUpAndDown() {
        fun emitted(pitch: Double) = GatewayRecorder().also { rec ->
            play(compiled("""
            apiVersion: astera/v3
            kind: Skill
            id: eye-poles
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - {id: p, op: particle, particle: flame, frame: caster.eye, pos: {v: 1.0, w: 1.0, s: 0.0}, count: 1, at: 0}
                end: {final: true}
            """), 1L, emptyList(), 4, rec, pitchDeg = pitch)
        }.emittedAt.filter { it.second == "flame" }.map { it.third }
        val level = emitted(0.0)
        assertThat(level).hasSize(1)
        for (pitch in listOf(-90.0, 90.0, 89.9, -89.9, 80.0, 45.0)) {
            val out = emitted(pitch)
            assertThat(out).describedAs("a point is placed on the eye frame at pitch $pitch (no coplanar fault)").hasSize(1)
        }
        // pitch 0 keeps the world-up basis exactly: eye + forward 1 + up 1 (the recorder's eye height is whatever it reports)
        val eyeY = level[0].y - 1.0
        assertThat(level[0].x).isBetween(-1e-6, 1e-6)
        assertThat(level[0].z).isBetween(0.9999, 1.0001)
        // straight up / down: v is vertical and w is the head-up (horizontal), so the point stays sqrt(2) from the eye
        for (pitch in listOf(-90.0, 90.0)) {
            val p = emitted(pitch)[0]
            val d = Math.sqrt(p.x * p.x + (p.y - eyeY) * (p.y - eyeY) + p.z * p.z)
            assertThat(d).describedAs("pitch $pitch").isBetween(1.4141, 1.4143)
        }
    }

    @Test
    fun followStretchAndTrailTargetTakeAFrameId() {
        fun replay(top: String, motion: String, particle: String = ""): GatewayRecorder {
            val rec = GatewayRecorder()
            rec.faithfulPose = true
            val disp = if (motion.isBlank()) "" else "- {id: d, op: display, kind: block, block: stone, anchor: caster, scale: [0.3, 0.3, 0.3], for: 8, motion: [$motion]}"
            play(compiled("""
            apiVersion: astera/v3
            kind: Skill
            id: ke-frame-roles
            $top
            machine:
              start: s
              states:
                s:
                  for: 8
                  enter:
                    $disp
$particle
            """), 1L, emptyList(), 8, rec)
            return rec
        }
        val stretched = replay("frames:\n              - {id: foot, origin: caster, v: aim, w: up, s: v x w}", "{law: stretch, base: foot, tip: caster.eye, for: 4, step: 2}")
        val sized = stretched.writtenPoses.map { it.third.size }
        assertThat(sized).isNotEmpty
        assertThat(sized.maxOf { maxOf(it.x, it.y, it.z) }).describedAs("stretch between a declared frame and a built-in frame").isCloseTo(1.62, org.assertj.core.api.Assertions.within(1e-3))
        val followed = replay("frames:\n              - {id: head, origin: caster.eye, v: aim, w: up, s: v x w}", "{law: follow, anchor: head, for: 6, step: 3}")
        assertThat(followed.writtenPoses).isNotEmpty
        val builtin = replay("", "{law: follow, anchor: caster.eye, for: 6, step: 3}")
        assertThat(builtin.writtenPoses).isNotEmpty
        val trail = replay(
            "frames:\n              - {id: head, origin: caster.eye, v: aim, w: up, s: v x w}", "",
            "                    - {id: tr, op: particle, particle: trail, color: \"#ff0080\", target: head, duration: 12, anchor: caster, at: 0}",
        )
        assertThat(trail.calls.filter { it.verb == "particle" }).isNotEmpty
        assertThat(lastPlayer!!.faults()).isEmpty()
    }

    @Test
    fun aFragmentCallPassesACasterPointAsAFramesInArgument() {
        val callee = """
        ---
        apiVersion: astera/v3
        kind: Skill
        id: spark
        meta: {cooldown_ticks: 0, resource_cost: 0}
        frames_in: {spot: frame}
        machine:
          start: s
          states:
            s:
              for: 2
              then: e
              enter:
                - {id: pf, op: particle, particle: flame, frame: spot, pos: {v: 1.0}, count: 1, at: 0}
            e: {final: true}
        """.trimIndent()
        val caller = """
        apiVersion: astera/v3
        kind: Skill
        id: caller
        meta: {cooldown_ticks: 0, resource_cost: 0}
        machine:
          start: s
          states:
            s:
              for: 4
              then: e
              enter:
                - {id: c, use: skill:spark, frames: {spot: caster.eye}}
                - {id: direct, op: particle, particle: smoke, frame: caster.eye, pos: {v: 1.0}, count: 1, at: 0}
            e: {final: true}
        """.trimIndent()
        val rec = GatewayRecorder()
        play(compiled(caller + "\n" + callee), 1L, emptyList(), 6, rec)
        val viaCall = rec.emittedAt.filter { it.second == "flame" }.map { it.third }
        val direct = rec.emittedAt.filter { it.second == "smoke" }.map { it.third }
        assertThat(viaCall).hasSize(1)
        assertThat(viaCall[0].distance(direct[0])).describedAs("caster.eye passed as the frame argument lands where the caller's own caster.eye row does").isLessThan(1e-9)
    }

    @Test
    fun aBuiltInCasterFrameNeedsNoDeclarationAndAWeaponPointIsReadFromTheItemsFramesTable() {
        fun emitted(frames: String, row: String, item: String = "") = GatewayRecorder().also { rec ->
            play(compiled("""
            apiVersion: astera/v3
            kind: Skill
            id: builtin-frames
            $item
            params: {reach: {type: number, default: 1.5}}
            $frames
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: end
                  do:
                    - $row
                end: {final: true}
            """), 1L, emptyList(), 4, rec)
        }.emittedAt.filter { it.second == "flame" }.map { it.third }
        val declared = emitted("frames:\n              - {id: eye, origin: caster.eye, v: aim, w: up, s: v x w}", "{id: p, op: particle, particle: flame, frame: eye, pos: {v: 1.0, w: 0.5, s: 0.25}, count: 1, at: 0}")
        val builtin = emitted("", "{id: p, op: particle, particle: flame, frame: caster.eye, pos: {v: 1.0, w: 0.5, s: 0.25}, count: 1, at: 0}")
        assertThat(builtin).hasSize(1)
        assertThat(builtin[0].distance(declared[0])).describedAs("caster.eye with no declaration = the declared eye frame").isLessThan(1e-9)
        val viaParam = emitted("", "{id: p, op: particle, particle: flame, frame: caster.eye, pos: {v: \"\${reach}\"}, count: 1, at: 0}")
        val literal = emitted("", "{id: p, op: particle, particle: flame, frame: caster.eye, pos: {v: 1.5}, count: 1, at: 0}")
        assertThat(viaParam[0].distance(literal[0])).describedAs("a top-level \${param} reads like the literal").isLessThan(1e-9)
        val hand = emitted("", "{id: p, op: particle, particle: flame, frame: caster.hand_r, count: 1, at: 0}")
        val muzzle = emitted("", "{id: p, op: particle, particle: flame, frame: weapon.muzzle, count: 1, at: 0}", "item: revolver")
        val tip = emitted("", "{id: p, op: particle, particle: flame, frame: weapon.tip, count: 1, at: 0}", "item: revolver")
        assertThat(muzzle[0].distance(tip[0])).describedAs("the revolver muzzle is its tip").isLessThan(1e-9)
        assertThat(muzzle[0].distance(hand[0])).describedAs("muzzle is about 0.7 from the grip (v 0.635 / w 0.286)").isBetween(0.65, 0.75)
    }

    private fun victimAt(id: UUID, x: Double, z: Double) = HitCandidate(id, Vec3(x, 0.0, z), 0.0, HitBox(Vec3(x - 0.3, 0.0, z - 0.3), Vec3(x + 0.3, 1.8, z + 0.3)), 1.62)

    private fun hitSkill(id: String, frames: String, vararg handler: String): CompiledSkill = compiled(
        """
        apiVersion: astera/v3
        kind: Skill
        id: $id
        frames:
        ${"  " + frames}
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
        ${handler.joinToString("\n        ") { "              " + it.trim() }}
        """,
    )

    private fun playHit(skill: CompiledSkill, victims: List<HitCandidate>): GatewayRecorder {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = victims
        play(skill, 1L, emptyList(), 8, rec)
        return rec
    }

    @Test
    fun aContactFrameOnTheVictimEventMatchesTheContactAnchor() {
        val v = listOf(victimAt(UUID(0L, 31L), -1.0, 3.0), victimAt(UUID(0L, 32L), 1.5, 4.0))
        val old = playHit(
            hitSkill("contact-anchor", "[]", "- {id: fx, op: particle, particle: flame, anchor: contact, count: 1}"),
            v,
        )
        val framed = playHit(
            hitSkill(
                "contact-frame",
                "- {id: hitc, on: event.victim, each: true, origin: contact, v: incoming, w: up, s: v x w}",
                "- {id: fx, op: particle, particle: flame, frame: hitc, count: 1}",
            ),
            v,
        )
        val a = old.emittedAt.filter { it.second == "flame" }
        val b = framed.emittedAt.filter { it.second == "flame" }
        assertThat(a).describedAs("old form emits").isNotEmpty()
        assertThat(b.map { it.first }).isEqualTo(a.map { it.first })
        a.zip(b).forEach { (x, y) ->
            assertThat(y.third.x).isCloseTo(x.third.x, org.assertj.core.data.Offset.offset(1e-6))
            assertThat(y.third.y).isCloseTo(x.third.y, org.assertj.core.data.Offset.offset(1e-6))
            assertThat(y.third.z).isCloseTo(x.third.z, org.assertj.core.data.Offset.offset(1e-6))
        }
    }

    @Test
    fun aVictimFeetFrameOnTheIncomingFeetAxisMatchesTheWorldKnockbackExpression() {
        val v = listOf(victimAt(UUID(0L, 33L), -1.0, 3.0), victimAt(UUID(0L, 34L), 2.0, 5.0))
        val old = playHit(
            hitSkill("kb-expr", "[]", "- {id: kb, op: knockback, target: event.victim, direction: \"= normalize(event.victim - pos(caster)) + vec(0.0, 0.3, 0.0)\", strength: 1.2}"),
            v,
        )
        val framed = playHit(
            hitSkill(
                "kb-frame",
                "- {id: push, on: event.victim, each: true, origin: victim.feet, v: incoming.feet, w: up, s: v x w}",
                "- {id: kb, op: knockback, target: event.victim, frame: push, dir: {v: 1.0, w: 0.3}, strength: 1.2}",
            ),
            v,
        )
        val a = old.calls.filter { it.payload.contains("impulse") }
        val b = framed.calls.filter { it.payload.contains("impulse") }
        assertThat(a).describedAs("old form pushes").hasSize(2)
        assertThat(b.map { it.tick to it.payload }).isEqualTo(a.map { it.tick to it.payload })
    }
}
