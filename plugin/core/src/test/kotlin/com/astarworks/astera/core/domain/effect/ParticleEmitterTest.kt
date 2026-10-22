package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ParticleEmitterTest {

    private val white = ParticleColor(255, 255, 255)

    private fun def(
        shape: ParticleShape = ParticleShape.Point,
        count: Int = 1,
        velocity: ParticleVelocity? = null,
        data: ParticleData? = null,
        key: String = "end_rod",
    ) = ParticleDefinition(
        id = ParticleId("t"),
        shape = shape,
        color = white,
        count = count,
        lifetimeTicks = 10,
        vanillaParticle = key,
        velocity = velocity,
        data = data,
    )

    private val origin = Vec3(10.0, 64.0, -3.0)

    @Test
    fun `a plain definition plans one legacy spawn with force defaulting to true`() {
        val planned = ParticleEmitter.plan(def(shape = ParticleShape.Burst(0.5), count = 7), origin)
        val p = planned.single()
        assertThat(p.at).isEqualTo(origin)
        assertThat(p.spec.count).isEqualTo(7)
        assertThat(p.spec.offset).isEqualTo(Vec3(0.5, 0.5, 0.5))
        assertThat(p.spec.force).isTrue()
    }

    @Test
    fun `velocity from_caster_look spawns count 0 particles with offset = direction and extra = speed`() {
        val look = Vec3(0.0, 0.0, 5.0)
        val planned = ParticleEmitter.plan(
            def(count = 3, velocity = ParticleVelocity(VelocityDirection.FromCasterLook, speed = 0.4)),
            origin,
            ParticleEmitContext(look = look),
        )
        assertThat(planned).hasSize(3)
        planned.forEach {
            assertThat(it.spec.count).isZero()
            assertThat(it.spec.offset).isEqualTo(Vec3(0.0, 0.0, 1.0))
            assertThat(it.spec.extra).isEqualTo(0.4)
        }
    }

    @Test
    fun `velocity up and custom ignore the context`() {
        val up = ParticleEmitter.plan(def(velocity = ParticleVelocity(VelocityDirection.Up, 0.2)), origin).single()
        assertThat(up.spec.offset).isEqualTo(Vec3(0.0, 1.0, 0.0))
        val custom = ParticleEmitter.plan(
            def(velocity = ParticleVelocity(VelocityDirection.Custom(Vec3(3.0, 0.0, 4.0)), 0.2)),
            origin,
        ).single()
        assertThat(custom.spec.offset.x).isCloseTo(0.6, within(1e-9))
        assertThat(custom.spec.offset.z).isCloseTo(0.8, within(1e-9))
    }

    @Test
    fun `along_tangent uses the element tangent and outward uses the centre`() {
        val tangent = ParticleEmitter.plan(
            def(velocity = ParticleVelocity(VelocityDirection.AlongTangent, 0.3)),
            origin,
            ParticleEmitContext(tangent = Vec3(-2.0, 0.0, 0.0)),
        ).single()
        assertThat(tangent.spec.offset).isEqualTo(Vec3(-1.0, 0.0, 0.0))

        val outward = ParticleEmitter.plan(
            def(velocity = ParticleVelocity(VelocityDirection.Outward, 0.3)),
            origin,
            ParticleEmitContext(center = Vec3(10.0, 64.0, -5.0)),
        ).single()
        assertThat(outward.spec.offset.z).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `spread jitters each particle inside the cone`() {
        val planned = ParticleEmitter.plan(
            def(count = 40, velocity = ParticleVelocity(VelocityDirection.Custom(Vec3(0.0, 0.0, 1.0)), 0.5, spreadDegrees = 10.0)),
            origin,
            ParticleEmitContext(random = Random(1)),
        )
        val cosMax = Math.cos(Math.toRadians(10.0))
        planned.forEach {
            assertThat(it.spec.offset.length()).isCloseTo(1.0, within(1e-9))
            assertThat(it.spec.offset.z).isGreaterThanOrEqualTo(cosMax - 1e-9)
        }
        assertThat(planned.map { it.spec.offset }.toSet().size).isGreaterThan(1)
    }

    @Test
    fun `ring samples points on the circle around the origin`() {
        val planned = ParticleEmitter.plan(def(shape = ParticleShape.Ring(2.0, 8)), origin)
        assertThat(planned).hasSize(8)
        planned.forEach {
            val d = it.at - origin
            assertThat(d.y).isCloseTo(0.0, within(1e-9))
            assertThat(Math.hypot(d.x, d.z)).isCloseTo(2.0, within(1e-9))
            assertThat(it.spec.count).isEqualTo(1)
        }
        assertThat(planned.map { it.at }.toSet()).hasSize(8)
    }

    @Test
    fun `ring with outward velocity points away from the centre and with tangent along the ring`() {
        val outward = ParticleEmitter.plan(
            def(shape = ParticleShape.Ring(2.0, 6), velocity = ParticleVelocity(VelocityDirection.Outward, 0.3)),
            origin,
        )
        outward.forEach {
            val radial = (it.at - origin).normalize()
            assertThat(it.spec.offset.dot(radial)).isCloseTo(1.0, within(1e-9))
        }
        val tangent = ParticleEmitter.plan(
            def(shape = ParticleShape.Ring(2.0, 6), velocity = ParticleVelocity(VelocityDirection.AlongTangent, 0.3)),
            origin,
        )
        tangent.forEach {
            val radial = (it.at - origin).normalize()
            assertThat(it.spec.offset.dot(radial)).isCloseTo(0.0, within(1e-9))
        }
    }

    @Test
    fun `arc is oriented by the caster look`() {
        // look +X (yaw 90 deg in MC terms): forward arc angle 0 lands on +X
        val planned = ParticleEmitter.plan(
            def(shape = ParticleShape.Arc(3.0, 0.0, 0.0, 1)),
            origin,
            ParticleEmitContext(look = Vec3(1.0, 0.0, 0.0)),
        )
        val d = planned.single().at - origin
        assertThat(d.x).isCloseTo(3.0, within(1e-6))
        assertThat(d.z).isCloseTo(0.0, within(1e-6))
    }

    @Test
    fun `line with points runs forward and helix rises`() {
        val line = ParticleEmitter.plan(def(shape = ParticleShape.Line(4.0, points = 5)), origin, ParticleEmitContext(look = Vec3(0.0, 0.0, 1.0)))
        assertThat(line.map { (it.at - origin).z }).containsExactly(0.0, 1.0, 2.0, 3.0, 4.0)

        val helix = ParticleEmitter.plan(def(shape = ParticleShape.Helix(1.0, 3.0, 2.0, 10)), origin)
        assertThat(helix).hasSize(10)
        assertThat((helix.first().at - origin).y).isCloseTo(0.0, within(1e-9))
        assertThat((helix.last().at - origin).y).isCloseTo(3.0, within(1e-9))
    }

    @Test
    fun `sphere with points stays on the sphere`() {
        val planned = ParticleEmitter.plan(def(shape = ParticleShape.Sphere(2.0, points = 20)), origin)
        assertThat(planned).hasSize(20)
        planned.forEach { assertThat((it.at - origin).length()).isCloseTo(2.0, within(1e-9)) }
    }

    @Test
    fun `trail offset and toward targets resolve to absolute positions`() {
        val offsetTrail = ParticleEmitter.plan(
            def(key = "trail", data = ParticleData.Trail(ParticleTarget.Offset(Vec3(0.0, 2.0, 0.0)), white, 12)),
            origin,
        ).single().spec.data as ParticleData.Trail
        assertThat(offsetTrail.target).isEqualTo(ParticleTarget.Absolute(Vec3(10.0, 66.0, -3.0)))

        val toward = ParticleEmitter.plan(
            def(key = "trail", data = ParticleData.Trail(ParticleTarget.Toward, white, 12)),
            origin,
            ParticleEmitContext(toward = Vec3(0.0, 70.0, 0.0)),
        ).single().spec.data as ParticleData.Trail
        assertThat(toward.target).isEqualTo(ParticleTarget.Absolute(Vec3(0.0, 70.0, 0.0)))
    }

    @Test
    fun `source and viewers reach the spec`() {
        val id = com.astarworks.astera.core.domain.player.PlayerId(java.util.UUID.randomUUID())
        val spec = ParticleEmitter.plan(
            def().copy(viewers = ParticleViewers.OTHERS),
            origin,
            ParticleEmitContext(source = id),
        ).single().spec
        assertThat(spec.source).isEqualTo(id)
        assertThat(spec.viewers).isEqualTo(ParticleViewers.OTHERS)
    }
}
