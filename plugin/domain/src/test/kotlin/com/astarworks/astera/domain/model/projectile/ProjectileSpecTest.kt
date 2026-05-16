package com.astarworks.astera.domain.model.projectile

import com.astarworks.astera.domain.animation.Easing
import com.astarworks.astera.domain.model.effect.ParticleSpec
import com.astarworks.astera.domain.model.effect.SoundSpec
import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.status.StatusEffectId
import com.astarworks.astera.domain.model.weapon.DamageAttribute
import com.astarworks.astera.domain.model.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ProjectileSpecTest {

    private val physicalDmg = DamageProfile(base = 5.0, attribute = DamageAttribute.PHYSICAL)

    @Test
    fun `ProjectileSpec requires at least one onHit effect`() {
        assertThatThrownBy {
            ProjectileSpec(
                motion = MotionProfile.Linear(initialSpeed = 30.0),
                hit = HitProfile.Sphere(radius = 0.5),
                lifetime = LifetimePolicy.UntilHit,
                onHit = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("onHit must contain at least one effect")
    }

    @Test
    fun `MotionProfile Linear rejects non-positive speed and out-of-range drag`() {
        assertThatThrownBy { MotionProfile.Linear(initialSpeed = 0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MotionProfile.Linear(initialSpeed = 10.0, drag = -0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MotionProfile.Linear(initialSpeed = 10.0, drag = 1.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MotionProfile Ballistic accepts positive gravity and drag in range`() {
        val m = MotionProfile.Ballistic(initialSpeed = 25.0, gravity = 9.8, drag = 0.05)
        assertThat(m.initialSpeed).isEqualTo(25.0)
        assertThat(m.gravity).isEqualTo(9.8)
    }

    @Test
    fun `MotionProfile Beam has infinite initial speed`() {
        assertThat(MotionProfile.Beam.initialSpeed).isEqualTo(Double.POSITIVE_INFINITY)
    }

    @Test
    fun `HitProfile rejects non-positive dimensions`() {
        assertThatThrownBy { HitProfile.Sphere(radius = 0.0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HitProfile.Box(width = 1.0, height = 0.0, depth = 1.0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HitProfile.Sweep(radius = -1.0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `LifetimePolicy invariants`() {
        assertThatThrownBy { LifetimePolicy.Ticks(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LifetimePolicy.Distance(0.0) }.isInstanceOf(IllegalArgumentException::class.java)
        // UntilHit is a singleton — must not throw
        assertThat(LifetimePolicy.UntilHit).isSameAs(LifetimePolicy.UntilHit)
    }

    @Test
    fun `ProjectileVisual ParticleTrail rejects non-positive intervalTicks`() {
        val p = ParticleSpec(key = "vanilla:spark", count = 1)
        assertThatThrownBy { ProjectileVisual.ParticleTrail(particle = p, intervalTicks = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `OnHitEffect Explode requires positive radius`() {
        assertThatThrownBy {
            OnHitEffect.Explode(radius = 0.0, damage = physicalDmg, falloff = Easing.LINEAR)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `composition allows arbitrary mixing of axes`() {
        // Demonstrates the design intent — a guided homing missile that explodes
        // on impact, leaves a particle trail, and dies after 5 seconds.
        val missile = ProjectileSpec(
            motion = MotionProfile.Homing(
                initialSpeed = 15.0,
                turnRatePerTick = 0.1,
                acquisitionRadius = 20.0,
            ),
            hit = HitProfile.Sweep(radius = 0.5),
            lifetime = LifetimePolicy.Ticks(maxTicks = 100),
            onHit = listOf(
                OnHitEffect.Explode(radius = 4.0, damage = physicalDmg, falloff = Easing.EASE_OUT_QUAD),
                OnHitEffect.SpawnParticle(ParticleSpec(key = "vanilla:explosion", count = 1)),
                OnHitEffect.PlaySound(SoundSpec(key = "vanilla:entity.generic.explode")),
            ),
            visual = ProjectileVisual.ParticleTrail(
                particle = ParticleSpec(key = "vanilla:flame", count = 1),
                intervalTicks = 1,
            ),
        )
        assertThat(missile.onHit).hasSize(3)
        assertThat(missile.motion).isInstanceOf(MotionProfile.Homing::class.java)
    }

    @Test
    fun `SpawnChild composes a sub-projectile`() {
        val sub = ProjectileSpec(
            motion = MotionProfile.Linear(initialSpeed = 10.0),
            hit = HitProfile.Sphere(radius = 0.3),
            lifetime = LifetimePolicy.Ticks(20),
            onHit = listOf(OnHitEffect.DealDamage(physicalDmg)),
        )
        val parent = ProjectileSpec(
            motion = MotionProfile.Ballistic(initialSpeed = 20.0, gravity = 5.0),
            hit = HitProfile.Sphere(radius = 0.5),
            lifetime = LifetimePolicy.UntilHit,
            onHit = listOf(OnHitEffect.SpawnChild(sub)),
        )
        val spawn = parent.onHit.single() as OnHitEffect.SpawnChild
        assertThat(spawn.sub).isEqualTo(sub)
    }

    @Test
    fun `OnHitEffect InflictStatus carries a status id and duration`() {
        val burn = OnHitEffect.InflictStatus(
            statusId = StatusEffectId("burn"),
            duration = 3.seconds,
        )
        assertThat(burn.statusId.value).isEqualTo("burn")
        assertThat(burn.duration).isEqualTo(3.seconds)
    }

    @Test
    fun `GunSpec invariants`() {
        assertThatThrownBy {
            GunSpec(
                ammoCapacity = 0,
                reloadDuration = 2.seconds,
                shotCooldown = 100.0.seconds,
                projectile = ProjectileSpec(
                    motion = MotionProfile.Linear(30.0),
                    hit = HitProfile.Sphere(0.5),
                    lifetime = LifetimePolicy.Ticks(40),
                    onHit = listOf(OnHitEffect.DealDamage(physicalDmg)),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
