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
import java.nio.file.Files
import java.util.UUID

/**
 * The shared `parry` part (founder Q5 = A, ADR-0303 section 9) played through the real [TablePlayer] by its two shipped callers,
 * couture-rapier-def and bastion-greatsword-def: a hit from inside the guarded arc during the window is absorbed (no damage, the attacker's
 * `on.absorbed` runs, no `on.hit`, the spark burst plays); a hit from behind, or after the window, lands.
 */
class ParryPartScenarioTest : SkillEngineScenario() {
    private val foe = UUID(0L, 11L)

    private fun shipped(id: String): CompiledSkill {
        val file = contentDir.resolve("skills/$id/skill.yaml")
        return when (val r = compileText("$id/skill.yaml", Files.readString(file))) {
            is Result.Success -> r.value
            is Result.Failure -> error("$id: ${r.error.message}")
        }
    }

    /** One melee hit at tick [at] from the origin onto the victim standing 3 blocks away. */
    private fun attack(at: Int): CompiledSkill = when (
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: strike
            machine:
              start: s
              states:
                s:
                  for: ${at + 4}
                  do:
                    - id: sel
                      op: hit
                      at: $at
                      anchor: caster
                      shape: {sphere: 8.0}
                      targets: enemies
                      ignore_invulnerability: false
                      damage: {physical: 1.0}
                      on:
                        hit:
                          - {id: landed, op: sound, sound: "astera:landed", anchor: caster, at: 0}
                        absorbed:
                          - {id: parried, op: sound, sound: "astera:parried", anchor: victim.center, at: 0}
            """.trimIndent(),
        )
    ) {
        is Result.Success -> r.value
        is Result.Failure -> error(r.error.message)
    }

    private fun duel(victim: CompiledSkill, victimYaw: Double, hitAt: Int): GatewayRecorder {
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        rec.victims = listOf(HitCandidate(foe, Vec3(0.0, 0.0, 3.0), 0.0, HitBox(Vec3(-0.3, 0.0, 2.7), Vec3(0.3, 1.8, 3.3)), 1.62))
        rec.guardCaster = com.astarworks.astera.core.domain.geometry.math.Frame.fromYawPitch(Vec3(0.0, 0.0, 3.0), victimYaw, 0.0)
        rec.guardOther = Vec3(0.0, 0.0, 0.0)
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        player.hooks = rec.hooks
        fun start(skill: CompiledSkill, caster: UUID, yaw: Double, feet: Vec3) {
            rec.castItem = skill.def.item
            val request = CastRequest(skill = skill, caster = caster, world = WorldId("world"), casterFeet = feet, casterYawDeg = yaw, casterPitchDeg = 0.0, seed = 1L, startTick = 0)
            check(player.start(request, skill) is Result.Success) { "start failed" }
        }
        start(victim, foe, victimYaw, Vec3(0.0, 0.0, 3.0))
        start(attack(hitAt), rec.caster, 0.0, Vec3(0.0, 0.0, 0.0))
        for (now in 0..(hitAt + 12)) {
            rec.tick = now
            player.tick(now)
        }
        check(player.faults().isEmpty()) { "faulted: " + player.faults().joinToString("; ") { "${it.src}: ${it.message}" } }
        return rec
    }

    private fun GatewayRecorder.sounds(name: String) = calls.count { it.verb == "sound" && it.payload.startsWith("sound=astera:$name") }

    private val shippedGuards = listOf("couture-rapier-def" to 12, "bastion-greatsword-def" to 8)

    @Test
    fun parryPartSharedByTwoWeapons() {
        for ((id, hitAt) in shippedGuards) {
            val rec = duel(shipped(id), victimYaw = 180.0, hitAt = hitAt)
            assertThat(rec.sounds("parried")).describedAs("$id: on.absorbed").isEqualTo(1)
            assertThat(rec.sounds("landed")).describedAs("$id: no on.hit").isZero()
            assertThat(rec.calls.count { it.verb == "cancelIncomingHit" }).describedAs("$id: the hit is cancelled once").isEqualTo(1)
            assertThat(rec.calls.count { it.verb == "particle" && it.payload.contains("crit") }).describedAs("$id: spark burst").isGreaterThan(0)
        }
    }

    @Test
    fun parryIgnoresFromBehind() {
        // bastion guards 110 degrees in front: a hit from behind lands
        val rec = duel(shipped("bastion-greatsword-def"), victimYaw = 0.0, hitAt = 8)
        assertThat(rec.sounds("parried")).describedAs("not absorbed").isZero()
        assertThat(rec.sounds("landed")).describedAs("on.hit").isEqualTo(1)
        assertThat(rec.calls.count { it.verb == "cancelIncomingHit" }).isZero()
        // couture guards all around (arc 360): the same hit is parried
        val all = duel(shipped("couture-rapier-def"), victimYaw = 0.0, hitAt = 12)
        assertThat(all.sounds("parried")).describedAs("couture arc 360").isEqualTo(1)
    }

    @Test
    fun parryWindowClosesAfterItsTicks() {
        // couture window 30, bastion window 16: a hit after the window lands even from the front
        for ((id, hitAt) in listOf("couture-rapier-def" to 34, "bastion-greatsword-def" to 20)) {
            val rec = duel(shipped(id), victimYaw = 180.0, hitAt = hitAt)
            assertThat(rec.sounds("parried")).describedAs("$id: window over").isZero()
            assertThat(rec.sounds("landed")).describedAs("$id: on.hit").isEqualTo(1)
        }
    }
}
