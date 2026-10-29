package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
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
 * A hit the victim's `absorb: true` transition takes (founder 2026-10-29: "当たらなかった扱い"): the attacker's `on.hit` does not run, nothing
 * is damaged or counted as a hit, the attacker's `on.absorbed` runs with `event.victim` bound, and the op counts as a miss. Both casts run in
 * one real [TablePlayer], the way a duel on the server does; covers the `hit` op and the projectile.
 */
class AbsorbedHitScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 11L)

    private fun skill(yaml: String): CompiledSkill = when (val r = loadInline(yaml.trimIndent())) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    /** The victim's guard: `hurt` goes to `riposte` (`$absorb` = `absorb: true`, or nothing for a plain reaction). */
    private fun guardSkill(absorb: String) = skill(
        """
        apiVersion: astera/v3
        kind: Skill
        id: guard
        machine:
          start: guard
          states:
            guard:
              for: 30
              then: done
              on:
                - {event: hurt, $absorb to: riposte}
            riposte:
              for: 4
              then: done
              do:
                - {id: snd, op: sound, sound: "astera:riposte", anchor: caster, at: 0}
            done: { final: true }
        """,
    )

    private val meleeAttack = skill(
        """
        apiVersion: astera/v3
        kind: Skill
        id: strike
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
                  damage: {physical: 1.0}
                  on:
                    hit:
                      - {id: landed, op: sound, sound: "astera:landed", anchor: caster, at: 0}
                    miss:
                      - {id: whiff, op: sound, sound: "astera:whiff", anchor: caster, at: 0}
                    absorbed:
                      - {id: parried, op: sound, sound: "astera:parried", anchor: victim.center, at: 0}
        """,
    )

    private val projectileAttack = skill(
        """
        apiVersion: astera/v3
        kind: Skill
        id: bolt
        item: couture-rapier
        meta: {cooldown_ticks: 60, resource_cost: 0}
        anchors:
          - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: follow}
        machine:
          start: s
          states:
            s:
              for: 40
              do:
                - id: shot
                  op: projectile
                  at: 1
                  origin: muzzle
                  velocity: "= look(caster) * 1.0"
                  radius: 0.4
                  for: 30
                  hit: {targets: enemies, once: true, attach: false}
                  block: {mode: despawn}
                  on:
                    hit:
                      - {id: landed, op: sound, sound: "astera:landed", anchor: caster, at: 0}
                    absorbed:
                      - {id: parried, op: sound, sound: "astera:parried", anchor: victim.center, at: 0}
        """,
    )

    private fun duel(attacker: CompiledSkill, victim: CompiledSkill): GatewayRecorder {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, 3.0), 0.0, HitBox(Vec3(-0.3, 0.0, 2.7), Vec3(0.3, 1.8, 3.3)), 1.62))
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        player.hooks = rec.hooks
        fun start(skill: CompiledSkill, caster: UUID) {
            rec.castItem = skill.def.item
            val request = CastRequest(skill = skill, caster = caster, world = WorldId("world"), casterFeet = Vec3(0.0, 0.0, 0.0), casterYawDeg = 0.0, casterPitchDeg = 0.0, seed = 1L, startTick = 0)
            check(player.start(request, skill) is Result.Success) { "start failed" }
        }
        start(victim, foe)
        start(attacker, rec.caster)
        for (now in 0..24) {
            rec.tick = now
            player.tick(now)
        }
        check(player.faults().isEmpty()) { "faulted: " + player.faults().joinToString("; ") { "${it.src}: ${it.message}" } }
        return rec
    }

    private fun GatewayRecorder.sounds(name: String) = calls.count { it.verb == "sound" && it.payload.startsWith("sound=astera:$name") }

    @Test
    fun absorbedMeleeHitRunsOnAbsorbedNotOnHit() {
        val rec = duel(meleeAttack, guardSkill("absorb: true,"))
        assertThat(rec.sounds("parried")).describedAs("on.absorbed").isEqualTo(1)
        assertThat(rec.sounds("landed")).describedAs("on.hit").isZero()
        assertThat(rec.calls.none { it.verb == "damage" }).describedAs("an absorbed hit damages nobody").isTrue()
        // for the attacker the hit did not land: the op counts as a miss
        assertThat(rec.sounds("whiff")).describedAs("on.miss").isEqualTo(1)
        // the victim's cast saw the hurt, took the absorbing transition and cancelled the hit once
        assertThat(rec.sounds("riposte")).isEqualTo(1)
        assertThat(rec.calls.count { it.verb == "cancelIncomingHit" }).isEqualTo(1)
    }

    @Test
    fun withoutAbsorbTheHitLandsAsBefore() {
        val rec = duel(meleeAttack, guardSkill(""))
        assertThat(rec.sounds("landed")).isEqualTo(1)
        assertThat(rec.sounds("parried")).isZero()
        assertThat(rec.sounds("whiff")).isZero()
        assertThat(rec.calls.count { it.verb == "damage" }).isEqualTo(1)
        assertThat(rec.calls.none { it.verb == "cancelIncomingHit" }).isTrue()
    }

    @Test
    fun absorbedProjectileRunsOnAbsorbedNotOnHit() {
        val rec = duel(projectileAttack, guardSkill("absorb: true,"))
        assertThat(rec.sounds("parried")).describedAs("on.absorbed").isEqualTo(1)
        assertThat(rec.sounds("landed")).describedAs("on.hit").isZero()
        assertThat(rec.sounds("riposte")).isEqualTo(1)
    }

    @Test
    fun projectileThatIsNotAbsorbedStillHits() {
        val rec = duel(projectileAttack, guardSkill(""))
        assertThat(rec.sounds("landed")).isEqualTo(1)
        assertThat(rec.sounds("parried")).isZero()
    }

    @Test
    fun onAbsorbedFollowsTheHitScopeRules() {
        // event.victim is bound in on.absorbed like in on.hit (the anchor above loads); a victim target outside a hit handler is refused
        val bad = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: bad
            machine:
              start: s
              states:
                s:
                  for: 6
                  do:
                    - {id: x, op: damage, target: event.victim, damage: {physical: 1.0}}
            """.trimIndent(),
        )
        assertThat(bad).isInstanceOf(Result.Failure::class.java)
    }
}
