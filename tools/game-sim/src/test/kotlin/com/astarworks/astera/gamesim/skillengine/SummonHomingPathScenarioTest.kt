package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.DefaultCastRunner
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0313: the three engine primitives that make summoned, homing and curved-path weapons writable in YAML alone.
 * `homing` (a projectile bends toward the nearest enemy), `law: path` (a spline the display follows, forward = tangent) and
 * `summon` (an independent run owned by the caster that lasts out, or is cut by the caster's death / the match end).
 */
class SummonHomingPathScenarioTest : SkillEngineScenario() {

    // ---- homing ------------------------------------------------------------------------------------------------------

    private fun homingSkill(homing: String): CompiledSkill {
        val yaml = """
            apiVersion: astera/v3
            kind: Skill
            id: homing-probe
            item: couture-rapier
            meta: {cooldown_ticks: 60, resource_cost: 0}
            anchors:
              - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
            machine:
              start: cast
              states:
                cast:
                  for: 40
                  then: done
                  do:
                    - id: bolt
                      op: projectile
                      origin: muzzle
                      velocity: "= look(caster) * 1.0"
                      for: 30
                      radius: 0.4
                      $homing
                      hit: {targets: enemies, once: true, attach: false}
                      block: {mode: despawn}
                      on:
                        hit:
                          - {id: hurt, op: damage, target: event.victim, damage: {physical: 5.0}}
                done: {final: true}
        """.trimIndent()
        return (loadInline(yaml) as? Result.Success)?.value ?: error("expected a clean decode: ${loadInline(yaml)}")
    }

    /** Damage calls of a bolt flown at a victim that stands 8 blocks ahead and steps 1.5 blocks aside at tick 3. */
    private fun hitsOfSteppedVictim(skill: CompiledSkill): Int {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victimMotion = { t ->
            val x = if (t < 3) 0.0 else 1.5
            listOf(HitCandidate(UUID(0L, 21L), Vec3(x, 0.0, 8.0), 0.0, HitBox(Vec3(x - 0.3, 0.0, 7.7), Vec3(x + 0.3, 1.8, 8.3)), 1.62))
        }
        play(skill, seed = 1L, events = emptyList(), ticks = 40, rec = rec)
        return rec.calls.count { it.verb == "damage" }
    }

    @Test
    fun homingBoltHitsAVictimWhoSteppedAsideAndStraightOneMisses() {
        assertThat(hitsOfSteppedVictim(homingSkill(""))).describedAs("a straight bolt flies past").isEqualTo(0)
        val homing = homingSkill("homing: {target: enemy, turn_deg_per_tick: 10, acquire: 20}")
        assertThat(hitsOfSteppedVictim(homing)).describedAs("a homing bolt bends onto the victim").isEqualTo(1)
        // deterministic: the same inputs give the same recording
        assertThat(hitsOfSteppedVictim(homing)).isEqualTo(1)
    }

    @Test
    fun homingWithNobodyInReachFliesStraight() {
        val homing = homingSkill("homing: {target: enemy, turn_deg_per_tick: 10, acquire: 2}")
        assertThat(hitsOfSteppedVictim(homing)).describedAs("the victim is never within acquire, so it is a straight flight").isEqualTo(0)
    }

    @Test
    fun homingIsStrictAtLoad() {
        for (bad in listOf("homing: {target: enemy, turn_deg_per_tick: 0, acquire: 10}", "homing: {target: enemy, turn_deg_per_tick: 400, acquire: 10}", "homing: {target: enemy, turn_deg_per_tick: 5, acquire: 0}", "homing: {target: ally, turn_deg_per_tick: 5, acquire: 10}")) {
            val yaml = """
                apiVersion: astera/v3
                kind: Skill
                id: homing-bad
                item: couture-rapier
                meta: {cooldown_ticks: 60, resource_cost: 0}
                anchors:
                  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
                machine:
                  start: cast
                  states:
                    cast:
                      for: 40
                      then: done
                      do:
                        - {id: bolt, op: projectile, origin: muzzle, velocity: "= look(caster) * 1.0", for: 30, radius: 0.4, $bad, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}}
                    done: {final: true}
            """.trimIndent()
            assertThat(loadInline(yaml)).describedAs(bad).isInstanceOf(Result.Failure::class.java)
        }
    }

    // ---- path --------------------------------------------------------------------------------------------------------

    private fun pathSkill(points: String): CompiledSkill {
        val yaml = """
            apiVersion: astera/v3
            kind: Skill
            id: path-probe
            item: couture-rapier
            meta: {cooldown_ticks: 60, resource_cost: 0}
            anchors:
              - {id: base, anchor: caster.feet, mode: snapshot}
            machine:
              start: s
              states:
                s:
                  for: 20
                  then: done
                  do:
                    - id: shell
                      op: display
                      kind: sprite
                      sprite: flame
                      anchor: base
                      size: [0.5, 0.5]
                      at: 0
                      for: 16
                      motion:
                        - {law: path, points: $points, at: 0, for: 16}
                done: {final: true}
        """.trimIndent()
        return (loadInline(yaml) as? Result.Success)?.value ?: error("expected a clean decode: ${loadInline(yaml)}")
    }

    private fun near(a: Vec3, x: Double, y: Double, z: Double) = Math.abs(a.x - x) < 1e-6 && Math.abs(a.y - y) < 1e-6 && Math.abs(a.z - z) < 1e-6

    @Test
    fun pathPlacesTheDisplayOnTheSplineAndFacesAlongTheTangent() {
        val rec = GatewayRecorder()
        play(pathSkill("[{forward: 0}, {forward: 4, up: 2}, {forward: 8}]"), seed = 1L, events = emptyList(), ticks = 24, rec = rec)
        val poses = rec.writtenPoses.map { it.third.frame }
        assertThat(poses).isNotEmpty()
        // the curve passes through its points at the matching progress: start, the apex at the half, the end
        val base = rec.casterPosition.y // the recorder's caster stand-in; the anchor is the caster's feet
        assertThat(near(poses.first().origin, 0.0, base, 0.0)).describedAs("start point; poses=" + poses.map { it.origin }).isTrue()
        val apex = poses.single { near(it.origin, 0.0, base + 2.0, 4.0) }
        assertThat(poses.all { Math.abs(it.origin.x) < 1e-9 && it.origin.z in 0.0..8.0 }).describedAs("stays in the vertical plane through the points").isTrue()
        // forward is the tangent: level at the apex, rising before it, falling after it
        assertThat(apex.forward().y).isEqualTo(0.0, org.assertj.core.data.Offset.offset(1e-6))
        assertThat(apex.forward().z).isGreaterThan(0.99)
        val i = poses.indexOf(apex)
        assertThat(poses[i - 2].forward().y).describedAs("rising").isGreaterThan(0.1)
        assertThat(poses[i + 2].forward().y).describedAs("falling").isLessThan(-0.1)
    }

    @Test
    fun twoPointPathIsAStraightLine() {
        val rec = GatewayRecorder()
        play(pathSkill("[{forward: 0}, {forward: 8}]"), seed = 1L, events = emptyList(), ticks = 24, rec = rec)
        val poses = rec.writtenPoses.map { it.third.frame }
        assertThat(poses.any { near(it.origin, 0.0, rec.casterPosition.y, 4.0) }).describedAs("middle; poses=" + poses.map { it.origin }).isTrue()
        assertThat(poses.all { Math.abs(it.origin.y - rec.casterPosition.y) < 1e-9 && Math.abs(it.origin.x) < 1e-9 }).isTrue()
    }

    @Test
    fun pathNeedsTwoPoints() {
        assertThat(runCatching { pathSkill("[{forward: 3}]") }.isFailure).isTrue()
    }

    // ---- summon ------------------------------------------------------------------------------------------------------

    private fun summonWorld(life: Int): Pair<CompiledSkill, CompiledSkill> {
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
                  for: 40
                  then: end
                  do:
                    - id: volley
                      at: 8
                      every: 10
                      times: 3
                      do:
                        - {id: pew, op: sound, sound: "astera:test-pew", anchor: spot, volume: 0.5, pitch: 1.0}
                        - {id: hurt, op: damage, target: {kind: enemies, around: spot, radius: 6.0}, damage: {physical: 2.0}}
                end: {final: true}
            ---
            apiVersion: astera/v3
            kind: Skill
            id: totem
            meta: {cooldown_ticks: 0, resource_cost: 0}
            anchors:
              - {id: plant, anchor: caster.feet, offset: {forward: 2}, mode: snapshot}
            machine:
              start: place
              states:
                place:
                  for: 2
                  then: end
                  do:
                    - {id: spawn_turret, op: summon, at: 0, skill: turret, anchor: plant, life: $life}
                end: {final: true}
        """.trimIndent()
        val loaded = (decodeAll("<inline>", yaml) as? Result.Success)?.value ?: error("expected a clean decode: ${decodeAll("<inline>", yaml)}")
        fun compiled(id: String) = (compileMain(loaded, id) as? Result.Success)?.value ?: error("$id did not compile")
        return compiled("totem") to compiled("turret")
    }

    private inner class Fx(life: Int) {
        val rec = GatewayRecorder()
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        val runner = DefaultCastRunner(player)
        val totem: CompiledSkill
        init {
            val (t, turret) = summonWorld(life)
            totem = t
            player.summonSkills = { id -> if (id == "turret") turret else null }
            rec.victims = listOf(HitCandidate(UUID(0L, 21L), Vec3(0.0, 0.0, 3.0), 0.0, HitBox(Vec3(-0.3, 0.0, 2.7), Vec3(0.3, 1.8, 3.3)), 1.62))
            rec.faithfulPose = true
        }

        fun cast() = runner.start(CastRequest(totem, rec.caster, WorldId("world"), Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0))

        fun run(from: Int, to: Int, each: (Int) -> Unit = {}) {
            for (t in from..to) { rec.tick = t; runner.tick(t); each(t) }
        }

        fun pews() = rec.calls.count { it.verb == "sound" && it.payload.contains("astera:test-pew") }
        fun hurts() = rec.calls.count { it.verb == "damage" }
    }

    @Test
    fun summonedTurretFiresThreeTimesThenExpires() {
        val fx = Fx(life = 30)
        fx.cast()
        var liveAt12 = -1
        fx.run(0, 60) { if (it == 12) liveAt12 = fx.player.running().size }
        assertThat(liveAt12).describedAs("the turret outlives the summoning cast (ended at tick 2)").isGreaterThanOrEqualTo(1)
        assertThat(fx.pews()).isEqualTo(3)
        assertThat(fx.hurts()).describedAs("each volley damages the enemy near it").isEqualTo(3)
        assertThat(fx.player.running()).describedAs("expired: nothing left running").isEmpty()
        assertThat(fx.player.faults()).isEmpty()
    }

    @Test
    fun lastsCutsTheSummonBeforeItsOwnMachineEnds() {
        val fx = Fx(life = 15)
        fx.cast()
        fx.run(0, 60)
        assertThat(fx.pews()).describedAs("start at 1, volleys at 9 and 19; cut at 16").isEqualTo(1)
        assertThat(fx.player.running()).isEmpty()
    }

    @Test
    fun casterDeathAndMatchEndCleanTheSummonUp() {
        for (viaAll in listOf(false, true)) {
            val fx = Fx(life = 30)
            fx.cast()
            fx.run(0, 12)
            val before = fx.pews()
            assertThat(before).isEqualTo(1)
            assertThat(fx.player.running()).isNotEmpty()
            val n = if (viaAll) fx.runner.cancelAll() else fx.runner.cancelAllOf(fx.rec.caster)
            assertThat(n).describedAs("viaAll=$viaAll").isGreaterThanOrEqualTo(1)
            fx.run(13, 60)
            assertThat(fx.pews()).describedAs("viaAll=$viaAll: no volley after the cut").isEqualTo(before)
            assertThat(fx.player.running()).describedAs("viaAll=$viaAll").isEmpty()
        }
    }

    @Test
    fun summonIsStrictAtLoad() {
        // a callee with no frames_in frame is not summonable
        val yaml = """
            apiVersion: astera/v3
            kind: Skill
            id: plain
            meta: {cooldown_ticks: 0, resource_cost: 0}
            machine: {start: s, states: {s: {for: 2, then: e}, e: {final: true}}}
            ---
            apiVersion: astera/v3
            kind: Skill
            id: caller
            meta: {cooldown_ticks: 0, resource_cost: 0}
            anchors:
              - {id: plant, anchor: caster.feet, mode: snapshot}
            machine:
              start: s
              states:
                s:
                  for: 2
                  then: e
                  do:
                    - {id: spawn_turret, op: summon, at: 0, skill: plain, anchor: plant, life: 10}
                e: {final: true}
        """.trimIndent()
        val loaded = (decodeAll("<inline>", yaml) as? Result.Success)?.value ?: error("expected a clean decode: ${decodeAll("<inline>", yaml)}")
        assertThat(compileMain(loaded, "caller")).isInstanceOf(Result.Failure::class.java)
    }
}
