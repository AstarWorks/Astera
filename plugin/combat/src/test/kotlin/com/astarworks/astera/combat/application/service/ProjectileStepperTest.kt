package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSkillBinding
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.combat.domain.projectile.ProjectileVisual
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pure stepping / ray math shared by the Paper and simulator projectile services. */
class ProjectileStepperTest {

    private val owner = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())
    private val world = WorldId("world")

    /** Records every impact so tests can assert the exactly-once contract. */
    private class RecordingHandler : ProjectileImpactHandler {
        val calls = mutableListOf<String>()
        override fun onHitEntity(flight: ProjectileImpactResolver.Flight, target: PlayerId?, at: Vec3) {
            calls += "entity@${at.x}"
        }
        override fun onHitBlock(flight: ProjectileImpactResolver.Flight, at: Vec3) {
            calls += "block@${at.x}"
        }
        override fun onExpire(flight: ProjectileImpactResolver.Flight, at: Vec3) {
            calls += "expire@${at.x}"
        }
    }

    /** Scriptable probe: a wall plane at x = [wallX] and a target box centred on x = [targetX]. */
    private class FakeProbe(
        private val wallX: Double? = null,
        private val targetX: Double? = null,
        private val targetId: PlayerId? = null,
        private val aim: Vec3? = null,
    ) : ProjectileWorldProbe {
        override fun firstContact(flight: ProjectileFlight, from: Vec3, to: Vec3): ProjectileContact? {
            val reach = ProjectileStepper.radiusOf(flight.spec.hit)
            val entityT = targetX?.let {
                ProjectileStepper.segmentBoxEntry(
                    from, to,
                    Vec3(it - 0.3 - reach, -reach, -0.3 - reach), Vec3(it + 0.3 + reach, 1.8 + reach, 0.3 + reach),
                )
            }
            val wallT = wallX?.takeIf { from.x < it && to.x >= it }?.let { (it - from.x) / (to.x - from.x) }
            return when {
                entityT != null && (wallT == null || entityT <= wallT) ->
                    ProjectileContact.Entity(checkNotNull(targetId), from + (to - from) * entityT)
                wallT != null -> ProjectileContact.Block(from + (to - from) * wallT)
                else -> null
            }
        }

        override fun homingTarget(flight: ProjectileFlight, radius: Double): Vec3? = aim
    }

    private fun spec(
        motion: MotionProfile = MotionProfile.Linear(initialSpeed = 60.0), // 3 blocks / tick
        lifetime: LifetimePolicy = LifetimePolicy.Ticks(10),
        visual: ProjectileVisual = ProjectileVisual.None,
        hit: HitProfile = HitProfile.Sphere(0.5),
    ) = ProjectileSpec(
        motion = motion,
        hit = hit,
        lifetime = lifetime,
        onHit = emptyList(),
        visual = visual,
        skill = ProjectileSkillBinding(
            skillId = SkillId("test"),
            phases = PhaseMechanics.Projectile(
                cast = emptyList(), hitEntity = emptyList(), hitBlock = emptyList(),
                miss = emptyList(), expire = emptyList(),
            ),
            castOrigin = Vec3.ZERO,
            worldId = world,
        ),
    )

    private fun flight(spec: ProjectileSpec, stepper: ProjectileStepper, dir: Vec3 = Vec3(1.0, 0.0, 0.0), from: Vec3 = Vec3.ZERO) =
        ProjectileFlight(ProjectileId.random(), owner, spec, from, stepper.launchVelocity(spec, dir), spawnTick = 0L)

    // ---- velocity conversion ---------------------------------------------------

    @Test
    fun `speed per tick is the per-second speed over 20 and beams use the beam range`() {
        assertThat(ProjectileStepper.speedPerTick(MotionProfile.Linear(60.0))).isEqualTo(3.0)
        assertThat(ProjectileStepper.speedPerTick(MotionProfile.Beam)).isEqualTo(ProjectileStepper.BEAM_RANGE)
        val stepper = ProjectileStepper({ null })
        assertThat(stepper.launchVelocity(spec(), Vec3(0.0, 0.0, 5.0)).z).isEqualTo(3.0)
    }

    // ---- stepping + exactly-once ------------------------------------------------

    @Test
    fun `an unobstructed projectile advances one velocity step per tick and expires exactly once`() {
        val handler = RecordingHandler()
        val stepper = ProjectileStepper({ handler })
        val f = flight(spec(lifetime = LifetimePolicy.Ticks(4)), stepper)
        val probe = FakeProbe()

        val outcomes = (1L..4L).map { stepper.step(f, it, probe) }

        assertThat(outcomes.dropLast(1)).allMatch { it == ProjectileStepOutcome.Flying }
        assertThat(outcomes.last()).isEqualTo(ProjectileStepOutcome.Expired(Vec3(12.0, 0.0, 0.0)))
        assertThat(handler.calls).containsExactly("expire@12.0")
        assertThat(f.travelled).isCloseTo(12.0, within(1e-9))
    }

    @Test
    fun `an entity contact resolves once at the contact point and stops the flight there`() {
        val handler = RecordingHandler()
        val stepper = ProjectileStepper({ handler })
        val f = flight(spec(), stepper)
        val probe = FakeProbe(targetX = 8.0, targetId = victim)
        val seenBefore = mutableListOf<ProjectileStepOutcome>()

        var tick = 0L
        var outcome: ProjectileStepOutcome
        do {
            tick++
            outcome = stepper.step(f, tick, probe) { seenBefore += it }
        } while (!outcome.isTerminal)

        // target box starts at 8 - 0.3 - 0.5 = 7.2: ticks cover 0-3, 3-6, 6-9 -> hit on the 3rd tick at x = 7.2
        assertThat(tick).isEqualTo(3L)
        val hit = outcome as ProjectileStepOutcome.HitEntity
        assertThat(hit.target).isEqualTo(victim)
        assertThat(hit.at.x).isCloseTo(7.2, within(1e-9))
        assertThat(f.position.x).isCloseTo(7.2, within(1e-9))
        assertThat(seenBefore).containsExactly(outcome) // beforeResolve ran for the terminal step only
        assertThat(handler.calls).hasSize(1)
        assertThat(handler.calls.single()).startsWith("entity@7.2")
    }

    @Test
    fun `a block in front of the target wins and resolves as a block hit`() {
        val handler = RecordingHandler()
        val stepper = ProjectileStepper({ handler })
        val f = flight(spec(), stepper)
        val probe = FakeProbe(wallX = 5.0, targetX = 8.0, targetId = victim)

        var outcome: ProjectileStepOutcome
        var tick = 0L
        do {
            outcome = stepper.step(f, ++tick, probe)
        } while (!outcome.isTerminal)

        assertThat(outcome).isEqualTo(ProjectileStepOutcome.HitBlock(Vec3(5.0, 0.0, 0.0)))
        assertThat(handler.calls).containsExactly("block@5.0")
    }

    @Test
    fun `a missing resolver still ends the flight without throwing`() {
        val stepper = ProjectileStepper({ null })
        val f = flight(spec(lifetime = LifetimePolicy.Ticks(1)), stepper)
        assertThat(stepper.step(f, 1L, FakeProbe())).isInstanceOf(ProjectileStepOutcome.Expired::class.java)
    }

    @Test
    fun `distance lifetime expires once the travelled distance is reached`() {
        val stepper = ProjectileStepper({ null })
        val f = flight(spec(lifetime = LifetimePolicy.Distance(8.0)), stepper)
        val probe = FakeProbe()
        val outcomes = (1L..3L).map { stepper.step(f, it, probe) }
        assertThat(outcomes[0]).isEqualTo(ProjectileStepOutcome.Flying) // 3
        assertThat(outcomes[1]).isEqualTo(ProjectileStepOutcome.Flying) // 6
        assertThat(outcomes[2]).isInstanceOf(ProjectileStepOutcome.Expired::class.java) // 9 >= 8
    }

    @Test
    fun `a beam resolves on its first step`() {
        val stepper = ProjectileStepper({ null })
        val f = flight(spec(motion = MotionProfile.Beam, lifetime = LifetimePolicy.UntilHit), stepper)
        val outcome = stepper.step(f, 1L, FakeProbe(targetX = 30.0, targetId = victim))
        assertThat(outcome).isInstanceOf(ProjectileStepOutcome.HitEntity::class.java)
        val missed = flight(spec(motion = MotionProfile.Beam, lifetime = LifetimePolicy.UntilHit), stepper)
        assertThat(stepper.step(missed, 1L, FakeProbe())).isInstanceOf(ProjectileStepOutcome.Expired::class.java)
    }

    // ---- forces ----------------------------------------------------------------

    @Test
    fun `ballistic motion loses vertical speed to gravity and linear drag shrinks the step`() {
        val stepper = ProjectileStepper({ null })
        val ballistic = flight(spec(motion = MotionProfile.Ballistic(initialSpeed = 60.0, gravity = 0.1)), stepper)
        stepper.step(ballistic, 1L, FakeProbe())
        assertThat(ballistic.velocity.y).isCloseTo(-0.1, within(1e-9))
        stepper.step(ballistic, 2L, FakeProbe())
        assertThat(ballistic.velocity.y).isCloseTo(-0.2, within(1e-9))

        val dragged = flight(spec(motion = MotionProfile.Linear(initialSpeed = 60.0, drag = 0.5)), stepper)
        stepper.step(dragged, 1L, FakeProbe())
        assertThat(dragged.velocity.x).isCloseTo(1.5, within(1e-9))
    }

    @Test
    fun `homing bends the velocity toward the aim point at the configured turn rate`() {
        val stepper = ProjectileStepper({ null })
        val homing = MotionProfile.Homing(initialSpeed = 60.0, turnRatePerTick = 0.5, acquisitionRadius = 20.0)
        val f = flight(spec(motion = homing), stepper)
        stepper.step(f, 1L, FakeProbe(aim = Vec3(0.0, 0.0, 10.0)))
        // blend of +x and +z, speed preserved
        assertThat(f.velocity.z).isGreaterThan(0.0)
        assertThat(f.velocity.length()).isCloseTo(3.0, within(1e-9))
    }

    // ---- ray math --------------------------------------------------------------

    @Test
    fun `segmentBoxEntry returns the entry fraction, zero when starting inside, null on a miss`() {
        val min = Vec3(4.0, -1.0, -1.0)
        val max = Vec3(6.0, 1.0, 1.0)
        assertThat(ProjectileStepper.segmentBoxEntry(Vec3(0.0, 0.0, 0.0), Vec3(8.0, 0.0, 0.0), min, max)).isEqualTo(0.5)
        assertThat(ProjectileStepper.segmentBoxEntry(Vec3(5.0, 0.0, 0.0), Vec3(9.0, 0.0, 0.0), min, max)).isEqualTo(0.0)
        assertThat(ProjectileStepper.segmentBoxEntry(Vec3(0.0, 5.0, 0.0), Vec3(8.0, 5.0, 0.0), min, max)).isNull() // passes above
        assertThat(ProjectileStepper.segmentBoxEntry(Vec3(0.0, 0.0, 0.0), Vec3(3.0, 0.0, 0.0), min, max)).isNull() // too short
        assertThat(ProjectileStepper.segmentBoxEntry(Vec3(0.0, 0.0, 3.0), Vec3(8.0, 0.0, 3.0), min, max)).isNull() // parallel, outside
    }

    @Test
    fun `hit radius inflates the box so a fast shot cannot tunnel past it`() {
        // a thin 0.2-wide target 3 blocks per tick away: the sphere radius still catches it
        val stepper = ProjectileStepper({ null })
        val f = flight(spec(hit = HitProfile.Sweep(0.4)), stepper)
        val probe = FakeProbe(targetX = 4.5, targetId = victim)
        assertThat(stepper.step(f, 1L, probe)).isEqualTo(ProjectileStepOutcome.Flying) // 0..3, box starts at 3.8
        assertThat(stepper.step(f, 2L, probe)).isInstanceOf(ProjectileStepOutcome.HitEntity::class.java)
    }

    // ---- trail -----------------------------------------------------------------

    @Test
    fun `trail points are evenly spaced, capped and end on the segment end`() {
        val pts = ProjectileStepper.trailPoints(Vec3.ZERO, Vec3(3.0, 0.0, 0.0))
        assertThat(pts).hasSize(5) // ceil(3.0 / 0.6)
        assertThat(pts.last()).isEqualTo(Vec3(3.0, 0.0, 0.0))
        assertThat(ProjectileStepper.trailPoints(Vec3.ZERO, Vec3(100.0, 0.0, 0.0))).hasSize(ProjectileStepper.TRAIL_MAX_POINTS)
        assertThat(ProjectileStepper.trailPoints(Vec3(1.0, 2.0, 3.0), Vec3(1.0, 2.0, 3.0))).containsExactly(Vec3(1.0, 2.0, 3.0))
    }

    @Test
    fun `the trail is emitted through the world gateway on the configured interval`() {
        val gateway = FakeWorldGateway()
        val stepper = ProjectileStepper({ null }, { gateway })
        val trail = ParticleSpec("flame", 1)
        val f = flight(spec(visual = ProjectileVisual(trail = listOf(trail), trailIntervalTicks = 2)), stepper)
        val probe = FakeProbe()
        repeat(4) { stepper.step(f, (it + 1).toLong(), probe) }
        // ages 1..4 -> only the even ages (2 and 4) emit; 5 points each
        assertThat(gateway.particles).hasSize(10)
        assertThat(gateway.particles).allMatch { it.particle == trail && it.world == world }
    }

    @Test
    fun `an invisible projectile emits no particles`() {
        val gateway = FakeWorldGateway()
        val stepper = ProjectileStepper({ null }, { gateway })
        val f = flight(spec(), stepper)
        stepper.step(f, 1L, FakeProbe())
        assertThat(gateway.particles).isEmpty()
    }
}
