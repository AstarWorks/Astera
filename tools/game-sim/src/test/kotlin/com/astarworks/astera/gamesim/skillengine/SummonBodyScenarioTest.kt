package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.DefaultCastRunner
import com.astarworks.astera.core.application.skillengine.runtime.player.ISkillBodyGateway
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0313 rev (founder 10/31: a summon may outlive its caster, be breakable, have hp): the per-summon `outlives_caster` and `body: {hp}` fields
 * and the `destroyed` hook. Teams go through the existing victim rules: an ally's hit changes nothing, an enemy's lowers hp.
 */
class SummonBodyScenarioTest : SkillEngineScenario() {

    private val enemy = UUID(0L, 77L)
    private val ally = UUID(0L, 78L)

    private class RecordingBodies : ISkillBodyGateway {
        val spawned = ArrayList<UUID>()
        val removed = ArrayList<UUID>()
        val live = LinkedHashSet<UUID>()
        override fun spawn(body: UUID, world: WorldId, feet: Vec3, width: Double, height: Double) { spawned += body; live += body }
        override fun remove(body: UUID) { removed += body; live -= body }
    }

    private fun docs(summonExtra: String, withBreak: Boolean): String {
        val onDestroyed = if (withBreak) "      on:\n        - {event: destroyed, to: broken}\n" else ""
        val broken = if (withBreak) {
            "    broken:\n      for: 3\n      then: end\n      do:\n        - {id: crack, op: sound, sound: \"astera:test-broken\", anchor: spot, volume: 0.5, pitch: 1.0}\n"
        } else ""
        return """
apiVersion: astera/v3
kind: Skill
id: turret
frames_in: {spot: anchor}
meta: {cooldown_ticks: 0, resource_cost: 0}
machine:
  start: watch
  states:
    watch:
      for: 100
      then: end
$onDestroyed      do:
        - id: volley
          at: 4
          every: 10
          times: 9
          do:
            - {id: pew, op: sound, sound: "astera:test-pew", anchor: spot, volume: 0.5, pitch: 1.0}
$broken    end: {final: true}
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
        - {id: spawn_turret, op: summon, at: 0, skill: turret, anchor: plant, life: 120$summonExtra}
    end: {final: true}
---
apiVersion: astera/v3
kind: Skill
id: bomb
meta: {cooldown_ticks: 0, resource_cost: 0}
anchors:
  - {id: here, anchor: caster.feet, mode: snapshot}
machine:
  start: boom
  states:
    boom:
      for: 2
      then: end
      do:
        - {id: blast, op: damage, at: 0, target: {kind: enemies, around: here, radius: 3.0}, damage: {physical: 25.0}}
    end: {final: true}
"""
    }

    private inner class Fx(summonExtra: String, withBreak: Boolean) {
        val rec = GatewayRecorder()
        val bodies = RecordingBodies()
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        val runner = DefaultCastRunner(player)
        val loaded: Map<String, CompiledSkill>

        init {
            val text = docs(summonExtra, withBreak)
            val l = (decodeAll("<inline>", text) as? Result.Success)?.value ?: error("expected a clean decode: ${decodeAll("<inline>", text)}")
            loaded = listOf("totem", "turret", "bomb").associateWith { id -> (compileMain(l, id) as? Result.Success)?.value ?: error("$id did not compile: ${compileMain(l, id)}") }
            player.summonSkills = { id -> if (id == "turret") loaded.getValue("turret") else null }
            player.bodyGateway = bodies
            rec.faithfulPose = true
        }

        fun cast(skill: String, caster: UUID, feet: Vec3 = Vec3(0.0, 0.0, 0.0), start: Int = 0) =
            runner.start(CastRequest(loaded.getValue(skill), caster, WorldId("world"), feet, 0.0, 0.0, 1L, startTick = start))

        fun run(from: Int, to: Int) { for (t in from..to) { rec.tick = t; runner.tick(t) } }
        fun count(sound: String) = rec.calls.count { it.verb == "sound" && it.payload.contains(sound) }
        fun body(): UUID = bodies.spawned.single()
    }

    @Test
    fun aSummonThatOutlivesItsCasterKeepsFiringAfterTheCasterDiesAndAMatchEndStillCutsIt() {
        val fx = Fx(", outlives_caster: true", withBreak = false)
        fx.cast("totem", fx.rec.caster)
        fx.run(0, 12)
        assertThat(fx.count("astera:test-pew")).isEqualTo(1)
        assertThat(fx.runner.cancelAllOf(fx.rec.caster)).describedAs("the caster's death cuts nothing it outlives").isEqualTo(0)
        fx.run(13, 40)
        assertThat(fx.count("astera:test-pew")).describedAs("volleys at 5, 15, 25, 35: still firing after the caster died").isEqualTo(4)
        assertThat(fx.runner.cancelAll()).isEqualTo(1)
        fx.run(41, 60)
        assertThat(fx.count("astera:test-pew")).isEqualTo(4)
        assertThat(fx.player.running()).isEmpty()
        assertThat(fx.player.faults()).isEmpty()
    }

    @Test
    fun aSummonWithoutTheFlagGoesWithItsCaster() {
        val fx = Fx("", withBreak = false)
        fx.cast("totem", fx.rec.caster)
        fx.run(0, 12)
        assertThat(fx.runner.cancelAllOf(fx.rec.caster)).isGreaterThanOrEqualTo(1)
        fx.run(13, 40)
        assertThat(fx.count("astera:test-pew")).isEqualTo(1)
        assertThat(fx.player.running()).isEmpty()
    }

    @Test
    fun anEnemyBreaksTheBodyThenTheBreakEffectPlaysAndTheRunEnds() {
        val fx = Fx(", outlives_caster: true, body: {hp: 20}", withBreak = true)
        fx.cast("totem", fx.rec.caster)
        fx.run(0, 6)
        assertThat(fx.bodies.spawned).hasSize(1)
        val body = fx.body()
        assertThat(fx.player.damageBody(body, enemy, 12.0)).describedAs("12 of 20 hp").isFalse()
        assertThat(fx.player.isBody(body)).isTrue()
        assertThat(fx.player.damageBody(body, enemy, 12.0)).describedAs("24 >= 20: broken").isTrue()
        assertThat(fx.player.isBody(body)).isFalse()
        assertThat(fx.bodies.removed).containsExactly(body)
        fx.run(7, 30)
        assertThat(fx.count("astera:test-broken")).describedAs("the destroyed hook plays the break state once").isEqualTo(1)
        assertThat(fx.count("astera:test-pew")).describedAs("start at 1, volley at 5 only: it fires no more once broken").isEqualTo(1)
        assertThat(fx.player.running()).isEmpty()
        assertThat(fx.player.faults()).isEmpty()
    }

    @Test
    fun anAlliesHitLeavesHpUnchangedAndSoDoesTheOwners() {
        val fx = Fx(", body: {hp: 20}", withBreak = true)
        fx.rec.allies += ally
        fx.cast("totem", fx.rec.caster)
        fx.run(0, 6)
        val body = fx.body()
        assertThat(fx.player.damageBody(body, ally, 100.0)).isFalse()
        assertThat(fx.player.damageBody(body, fx.rec.caster, 100.0)).isFalse()
        assertThat(fx.player.damageBody(body, enemy, 12.0)).describedAs("hp is still 20: 12 does not break it").isFalse()
        assertThat(fx.player.damageBody(body, enemy, 12.0)).isTrue()
    }

    @Test
    fun anEnemySkillsAreaDamageSeesTheBodyButItsOwnersTurretDoesNot() {
        val fx = Fx(", body: {hp: 20}", withBreak = true)
        fx.cast("totem", fx.rec.caster)
        fx.run(0, 6)
        val body = fx.body()
        assertThat(fx.player.isBody(body)).isTrue()
        fx.cast("bomb", enemy, feet = Vec3(0.0, 0.0, 2.0), start = 7)
        fx.run(7, 30)
        assertThat(fx.player.isBody(body)).describedAs("25 damage from an enemy area op breaks the 20 hp body").isFalse()
        assertThat(fx.count("astera:test-broken")).isEqualTo(1)
        assertThat(fx.player.faults()).isEmpty()
    }

    @Test
    fun withoutABodyNothingSpawnsAndNothingIsTargetable() {
        val fx = Fx(", outlives_caster: true", withBreak = false)
        fx.cast("totem", fx.rec.caster)
        fx.run(0, 6)
        assertThat(fx.bodies.spawned).isEmpty()
        assertThat(fx.player.damageBody(UUID(0L, 5L), enemy, 100.0)).isFalse()
        fx.cast("bomb", enemy, feet = Vec3(0.0, 0.0, 2.0), start = 7)
        fx.run(7, 30)
        assertThat(fx.count("astera:test-pew")).describedAs("the blast hurt nothing: the turret fires on").isGreaterThanOrEqualTo(3)
    }

    @Test
    fun theBodyIsRemovedWithTheRunWhateverEndsIt() {
        for (how in listOf("life", "cancelAll", "death")) {
            val fx = Fx(", body: {hp: 20}", withBreak = true)
            fx.cast("totem", fx.rec.caster)
            fx.run(0, 6)
            assertThat(fx.bodies.live).describedAs(how).hasSize(1)
            when (how) {
                "cancelAll" -> fx.runner.cancelAll()
                "death" -> fx.runner.cancelAllOf(fx.rec.caster)
            }
            fx.run(7, 200)
            assertThat(fx.bodies.live).describedAs("$how: no body entity left behind").isEmpty()
            assertThat(fx.player.isBody(fx.body())).describedAs(how).isFalse()
        }
    }

    @Test
    fun theDestroyedHookWithoutABodyIsALoadError() {
        val l = (decodeAll("<inline>", docs("", withBreak = true)) as? Result.Success)?.value ?: error("expected a clean decode")
        assertThat(compileMain(l, "totem")).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun bodyFieldsAreStrictAtLoad() {
        for (bad in listOf(", body: {hp: 0}", ", body: {hp: 5, width: -1}", ", body: {width: 1}", ", outlives_caster: \"= true\"", ", body: {hp: 5, depth: 1}")) {
            val decoded = decodeAll("<inline>", docs(bad, withBreak = false))
            val failed = decoded is Result.Failure || compileMain((decoded as Result.Success).value, "totem") is Result.Failure
            assertThat(failed).describedAs(bad).isTrue()
        }
    }
}
