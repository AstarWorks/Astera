package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.ProjectileContact
import com.astarworks.astera.combat.application.service.ProjectileFlight
import com.astarworks.astera.combat.application.service.ProjectileStepOutcome
import com.astarworks.astera.combat.application.service.ProjectileStepper
import com.astarworks.astera.combat.application.service.ProjectileWorldProbe
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The v3 pistol-shot bolt ends exactly one way: a shot into empty air expires without an impact cue,
 * a shot into the ground plays the block impact once, a shot at a player plays the body impact on the tick the shared stepper predicts.
 */
class ProjectileLifecycleScenarioTest {

    @Test
    fun `a missed projectile expires once and plays the miss cue once`() {
        val trace = scenario("projectile-expires") {
            val shooter = player("shooter")
            shooter.holds("pistol")
            shooter.rightClick()
            advance(80)
        }
        // pistol-shot's `fire` state plays one report; a bolt that expires in the air plays no impact cue.
        assertThat(soundCount(trace, GUN_SHOT)).isEqualTo(1)
        assertThat(soundCount(trace, GUN_IMPACT)).isZero()
        assertThat(soundCount(trace, GUN_IMPACT_BLOCK)).isZero()
    }

    @Test
    fun `a projectile fired at the ground hits a block once`() {
        val trace = scenario("projectile-hits-ground") {
            val shooter = player("shooter")
            shooter.holds("pistol")
            shooter.lookAt(Vec3(0.0, groundY, 6.0)) // down at the floor, 6 blocks ahead
            shooter.rightClick()
            advance(40)
        }
        assertThat(soundCount(trace, GUN_SHOT)).isEqualTo(1)
        assertThat(soundCount(trace, GUN_IMPACT_BLOCK)).`as`("the block impact plays once").isEqualTo(1)
        assertThat(soundCount(trace, GUN_IMPACT)).isZero()
    }

    @Test
    fun `a shot hits the target on exactly the tick the shared stepping predicts`() {
        // pistol-shot: launch_speed 3.2 blocks/tick, default hit radius 0.5, victim 12 blocks away.
        var predictedTicks = -1L
        val trace = scenario("projectile-hit-timing") {
            val shooter = player("shooter", at = Vec3(0.0, groundY, 0.0))
            val victim = player("victim", at = Vec3(0.0, groundY, 12.0))
            shooter.holds("pistol")
            shooter.faces(victim)
            val dir = shooter.direction
            val muzzle = shooter.eyePosition + dir * 0.5 // ProjectileHandler.MUZZLE_OFFSET

            // The very same stepper the sim (and Paper) run, against a bare victim box.
            val spec = ProjectileSpec(
                motion = MotionProfile.Linear(initialSpeed = 3.2 * 20.0),
                hit = HitProfile.Sphere(0.5),
                lifetime = LifetimePolicy.Ticks(30),
                onHit = emptyList(),
                skill = com.astarworks.astera.combat.domain.projectile.ProjectileSkillBinding(
                    skillId = com.astarworks.astera.core.domain.skill.SkillId("pistol-shot"),
                    castOrigin = shooter.position,
                    worldId = null,
                ),
            )
            val stepper = ProjectileStepper({ null })
            val flight = ProjectileFlight(ProjectileId.random(), shooter.id, spec, muzzle, stepper.launchVelocity(spec, dir), 0L)
            val probe = object : ProjectileWorldProbe {
                override fun firstContact(flight: ProjectileFlight, from: Vec3, to: Vec3): ProjectileContact? {
                    val t = ProjectileStepper.segmentBoxEntry(
                        from, to,
                        Vec3(victim.position.x - 0.3 - 0.5, victim.position.y - 0.5, victim.position.z - 0.3 - 0.5),
                        Vec3(victim.position.x + 0.3 + 0.5, victim.position.y + 1.8 + 0.5, victim.position.z + 0.3 + 0.5),
                    ) ?: return null
                    return ProjectileContact.Entity(victim.id, from + (to - from) * t)
                }
                override fun homingTarget(flight: ProjectileFlight, radius: Double): Vec3? = null
            }
            var tick = 0L
            do {
                tick++
                val outcome = stepper.step(flight, tick, probe)
            } while (outcome !is ProjectileStepOutcome.HitEntity && tick < 30)
            predictedTicks = tick

            shooter.rightClick()
            advance(20)
        }
        val spawnTick = soundTicks(trace, GUN_SHOT).single()
        val hitTick = soundTicks(trace, GUN_IMPACT).single()
        assertThat(predictedTicks).isEqualTo(4L)
        assertThat(hitTick - spawnTick).isEqualTo(predictedTicks)
    }

    private fun soundTicks(trace: java.nio.file.Path, key: String): List<Long> =
        ticks(trace).filter { t -> t["sounds"]!!.jsonArray.any { it.jsonObject["key"]!!.jsonPrimitive.content == key } }
            .map { it["tick"]!!.jsonPrimitive.content.toLong() }

    private fun soundCount(trace: java.nio.file.Path, key: String): Int =
        ticks(trace).flatMap { it["sounds"]!!.jsonArray }
            .count { it.jsonObject["key"]!!.jsonPrimitive.content == key }

    private fun ticks(trace: java.nio.file.Path) =
        Json.parseToJsonElement(Files.readString(trace)).jsonObject["ticks"]!!.jsonArray.map { it.jsonObject }

    private companion object {
        const val GUN_SHOT = "astera:weapons/gun-shot"
        const val GUN_IMPACT = "astera:weapons/gun-impact"
        const val GUN_IMPACT_BLOCK = "astera:weapons/gun-impact-block"
    }
}
