package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.AimPolicy
import com.astarworks.astera.bots.ItemSkillCatalog
import com.astarworks.astera.bots.TargetPolicy
import com.astarworks.astera.bots.TargetSelector
import com.astarworks.astera.bots.VelocityTracker
import com.astarworks.astera.bots.Weapon
import com.astarworks.astera.bots.WeaponKind
import com.astarworks.astera.bots.WeaponPolicy
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The bot AI decision policies ([TargetSelector], [AimPolicy], [ItemSkillCatalog], [WeaponPolicy] ranges), the very
 * objects BotBrain calls, driven with real sim entity positions: LOS, HP and threat weighting, memory after a LOS break,
 * lead aim on a moving target and the skill cast decision.
 */
class BotAiPolicyScenarioTest {

    private fun ScenarioScope.cand(
        e: SimEntity, los: Boolean = true, hp: Double? = null,
    ) = TargetPolicy.Candidate(
        id = e.id.hashCode(), name = e.name, team = "blue", spectating = false,
        x = e.position.x, y = e.position.y, z = e.position.z, hasLos = los, hpFraction = hp,
    )

    private fun ScenarioScope.pick(sel: TargetSelector, now: Long, me: SimEntity, vararg cs: TargetPolicy.Candidate) =
        sel.select(now, me.name, "red", me.position.x, me.position.y, me.position.z, cs.toList())

    @Test
    fun `selector ignores a nearest enemy behind a wall prefers low hp and the attacker and sticks to its target`() {
        scenario("bot-ai-target-scoring") {
            val red = player("red-1", team = "red")
            val near = player("blue-1", team = "blue")
            val far = player("blue-2", team = "blue")
            val weak = player("blue-3", team = "blue")
            startMatch("annihilation", mapOf(red to "red", near to "blue", far to "blue", weak to "blue"), stage = "annihilation-arena")
            near.moveTo(red.position + Vec3(0.0, 0.0, 6.0))
            far.moveTo(red.position + Vec3(0.0, 0.0, 20.0))
            weak.moveTo(red.position + Vec3(0.0, 0.0, 30.0))

            val sel = TargetSelector()
            assertThat(pick(sel, 0, red, cand(near, los = false), cand(far)).target?.name)
                .`as`("the wall-blocked nearest enemy is ignored").isEqualTo(far.name)

            val sel2 = TargetSelector()
            assertThat(pick(sel2, 0, red, cand(far), cand(weak, hp = 0.1)).target?.name)
                .`as`("a 10% hp enemy 10 blocks farther is preferred").isEqualTo(weak.name)

            val sel3 = TargetSelector()
            sel3.noteAttackedBy(far.id.hashCode(), 0)
            assertThat(pick(sel3, 100, red, cand(near), cand(far)).target?.name)
                .`as`("the enemy that just hit me beats a nearer one").isEqualTo(far.name)
            assertThat(pick(sel3, 6_000, red, cand(near), cand(far)).target?.name)
                .`as`("the threat bonus decays after its window: the much nearer enemy wins again").isEqualTo(near.name)

            val sel4 = TargetSelector()
            assertThat(pick(sel4, 0, red, cand(far)).target?.name).isEqualTo(far.name)
            near.moveTo(red.position + Vec3(0.0, 0.0, 15.0)) // 5 blocks nearer than far: inside the hysteresis
            assertThat(pick(sel4, 50, red, cand(far), cand(near)).target?.name)
                .`as`("no flip for a marginally nearer enemy").isEqualTo(far.name)
        }
    }

    @Test
    fun `selector remembers a target that broke line of sight and chases its last position then drops it`() {
        scenario("bot-ai-target-memory") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            blue.moveTo(red.position + Vec3(0.0, 0.0, 18.0))
            val seenAt = blue.position
            val sel = TargetSelector()
            assertThat(pick(sel, 0, red, cand(blue)).remembered).isFalse()

            blue.moveTo(red.position + Vec3(15.0, 0.0, 18.0)) // behind cover now
            val c = pick(sel, 1_000, red, cand(blue, los = false))
            assertThat(c.target?.name).isEqualTo(blue.name)
            assertThat(c.remembered).isTrue()
            assertThat(c.lastX).isEqualTo(seenAt.x)
            assertThat(c.lastZ).isEqualTo(seenAt.z)
            assertThat(WeaponPolicy.mayFire(18.0, hasLos = false)).`as`("a remembered target is chased, not shot").isFalse()

            assertThat(pick(sel, TargetSelector.MEMORY_MS + 500, red, cand(blue, los = false)).target)
                .`as`("dropped after the memory time").isNull()
        }
    }

    @Test
    fun `lead aim intercepts a strafing target at 30 blocks and gravity is compensated`() {
        scenario("bot-ai-lead-aim") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            val eye = red.position + Vec3(0.0, 1.62, 0.0)
            val tracker = VelocityTracker()
            var pos = red.position + Vec3(0.0, 0.0, 30.0)
            val v = Vec3(0.2, 0.0, 0.0) // 0.2 blocks/tick = sprint strafe
            for (i in 0..10) {
                blue.moveTo(pos)
                tracker.observe(1, i * 50L, pos.x, pos.y, pos.z)
                pos += v
            }
            val vel = tracker.velocity(1)
            assertThat(vel[0]).isBetween(0.18, 0.22)

            val tx = pos.x; val ty = pos.y + 1.0; val tz = pos.z
            val p = AimPolicy.GUN
            val lead = AimPolicy.solve(eye.x, eye.y, eye.z, tx, ty, tz, vel[0], vel[1], vel[2], p)
            val naive = AimPolicy.solve(eye.x, eye.y, eye.z, tx, ty, tz, 0.0, 0.0, 0.0, p)

            // Fly the bullet along the solved direction for the solved time and compare with the target's real position then.
            fun miss(s: AimPolicy.Solution): Double {
                val yr = Math.toRadians(s.yaw.toDouble()); val pr = Math.toRadians(s.pitch.toDouble())
                val dirX = -sin(yr) * cos(pr); val dirY = -sin(pr); val dirZ = cos(yr) * cos(pr)
                val t = s.flightTicks
                val bx = eye.x + dirX * p.speed * t; val by = eye.y + dirY * p.speed * t; val bz = eye.z + dirZ * p.speed * t
                val gx = tx + 0.2 * t; val gy = ty; val gz = tz
                return hypot(hypot(bx - gx, by - gy), bz - gz)
            }
            assertThat(miss(lead)).`as`("lead aim lands on the moving target").isLessThan(0.5)
            // The naive shot misses by strafe speed x flight time: 0.2 b/tick x (30.07 / GUN speed 3.2 = 9.4 ticks) = 1.88 blocks.
            // Bound = analytic 1.88 minus 0.08 tolerance (rounding of the integer flight ticks);
            // the old 2.0 was above the analytic value and could never hold at 30 blocks.
            assertThat(miss(naive)).`as`("aiming at the current position misses by ~1.88 blocks (analytic)").isGreaterThan(1.8)

            // Gravity: an arc projectile is aimed above the straight line.
            val straight = AimPolicy.solve(eye.x, eye.y, eye.z, tx, ty, tz, 0.0, 0.0, 0.0, AimPolicy.Profile(1.8, 0.0, 60))
            val arc = AimPolicy.solve(eye.x, eye.y, eye.z, tx, ty, tz, 0.0, 0.0, 0.0, AimPolicy.ARC)
            assertThat(arc.pitch).`as`("negative pitch = looking up").isLessThan(straight.pitch)
        }
    }

    @Test
    fun `ranged keeps its distance fires only with los and a sword bot dashes in from range on cooldown`() {
        scenario("bot-ai-engagement-and-skill") {
            val gun = Weapon(0, WeaponKind.RANGED, magazine = true)
            val sword = Weapon(1, WeaponKind.MELEE)
            assertThat(WeaponPolicy.move(gun, 5.0)).isEqualTo(WeaponPolicy.Move.BACK_OFF)
            assertThat(WeaponPolicy.move(gun, 18.0)).isEqualTo(WeaponPolicy.Move.STRAFE)
            assertThat(WeaponPolicy.move(gun, 33.0)).isEqualTo(WeaponPolicy.Move.APPROACH)
            assertThat(WeaponPolicy.move(gun, 18.0, hasLos = false)).isEqualTo(WeaponPolicy.Move.APPROACH)
            assertThat(WeaponPolicy.move(sword, 18.0)).isEqualTo(WeaponPolicy.Move.CLOSE_IN)
            assertThat(WeaponPolicy.mayFire(39.0, true)).isTrue()
            assertThat(WeaponPolicy.mayFire(41.0, true)).isFalse()

            val catalog = ItemSkillCatalog.load(GameSim.defaultContentDir().toFile())
            val skills = catalog.policyFor("Knight's Sword")
            assertThat(skills.decide(0, 3.0, true)).`as`("already in melee reach").isNull()
            assertThat(skills.decide(0, 12.0, false)).`as`("no line of sight").isNull()
            assertThat(skills.decide(0, 12.0, true)?.id).isEqualTo("sword-dash")
            assertThat(skills.decide(1_000, 12.0, true)).`as`("on cooldown").isNull()
            assertThat(skills.decide(5_100, 12.0, true)?.id).`as`("ready again").isEqualTo("sword-dash")
            assertThat(catalog.policyFor("Pistol").decide(0, 12.0, true)).`as`("the pistol's reload is not a combat skill").isNull()
        }
    }
}
