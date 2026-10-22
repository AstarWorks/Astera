package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.service.NoOpKnockbackGateway
import com.astarworks.astera.combat.application.service.StubCelEvaluator
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.combat.testfixtures.FakeProjectileService
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import com.astarworks.astera.testfixtures.effect.FakeParticleScheduler
import com.astarworks.astera.testfixtures.effect.FakeSoundScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ProjectileHandlerTest {

    private val world = WorldId("world")
    private val caster = PlayerId(UUID.randomUUID())
    private val skillId = SkillId("example-blast")

    private val projectiles = FakeProjectileService()
    private val particles = FakeParticleScheduler()
    private val sounds = FakeSoundScheduler()
    private val scheduler = FakeScheduler()
    private val dispatcher = MechanicDispatcher(
        applyDamage = null,
        applyStatus = null,
        worldGateway = FakeWorldGateway(),
        knockbackGateway = NoOpKnockbackGateway(),
        nearbyQuery = FakeNearbyTargetQuery(),
        broadcaster = null,
        celEvaluator = StubCelEvaluator(),
        scheduler = scheduler,
        eventBus = FakeDomainEventBus(),
        particleScheduler = particles,
        soundScheduler = sounds,
    )
    private val handler = ProjectileHandler(projectiles, dispatcher)

    private val params = SkillParams.ProjectileParams(
        launchSpeed = 1.8,
        maxLifetimeTicks = 60,
    )
    private val phases = PhaseMechanics.Projectile(
        cast = listOf(
            Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot")),
            Mechanic.SpawnParticle(Targeter.Caster, ParticleId("example-burst")),
        ),
    )

    private fun cast(pose: PlayerPose?) =
        SkillCast(caster, skillId, at = Vec3(10.0, 64.0, 10.0), atTick = 3L, pose = pose)

    @Test
    fun `launches along the caster look direction from the eye with skill params`() {
        val look = Vec3(0.0, 0.0, 1.0)
        val pose = PlayerPose.standing(world, Vec3(10.0, 64.0, 10.0), look)

        handler.handle(cast(pose), params, phases)

        val spawn = projectiles.spawned.single()
        assertThat(spawn.owner).isEqualTo(caster)
        assertThat(spawn.direction).isEqualTo(look)
        assertThat(spawn.origin).isEqualTo(pose.eyePosition + look * ProjectileHandler.MUZZLE_OFFSET)
        // launch_speed is blocks/tick -> MotionProfile blocks/second
        assertThat(spawn.spec.motion).isEqualTo(MotionProfile.Linear(initialSpeed = 36.0))
        assertThat(spawn.spec.lifetime).isEqualTo(LifetimePolicy.Ticks(60))
        assertThat(spawn.spec.hit).isEqualTo(HitProfile.Sphere(ProjectileHandler.DEFAULT_HIT_RADIUS))
    }

    @Test
    fun `hit_radius param feeds the hit profile`() {
        val pose = PlayerPose.standing(world, Vec3.ZERO, Vec3(1.0, 0.0, 0.0))

        handler.handle(cast(pose), params.copy(hitRadius = 1.25), phases)

        assertThat(projectiles.spawned.single().spec.hit).isEqualTo(HitProfile.Sphere(1.25))
    }

    @Test
    fun `spec carries the skill phases and cast context for impact resolution`() {
        val pose = PlayerPose.standing(world, Vec3(10.0, 64.0, 10.0), Vec3(1.0, 0.0, 0.0))

        handler.handle(cast(pose), params, phases)

        val binding = projectiles.spawned.single().spec.skill!!
        assertThat(binding.skillId).isEqualTo(skillId)
        assertThat(binding.phases).isEqualTo(phases)
        assertThat(binding.castOrigin).isEqualTo(Vec3(10.0, 64.0, 10.0))
        assertThat(binding.worldId).isEqualTo(world)
        assertThat(binding.pose).isEqualTo(pose)
    }

    @Test
    fun `cast phase particle and sound fire because the world is threaded through`() {
        val pose = PlayerPose.standing(world, Vec3(10.0, 64.0, 10.0), Vec3(1.0, 0.0, 0.0))

        handler.handle(cast(pose), params, phases)

        val particle = particles.scheduled.single().spec as ScheduledParticleSpec.EmitOnce
        val sound = sounds.scheduled.single().spec as ScheduledSoundSpec.EmitOnce
        assertThat(particle.world).isEqualTo(world)
        assertThat(sound.world).isEqualTo(world)
        assertThat(sound.position).isEqualTo(Vec3(10.0, 64.0, 10.0))
    }

    @Test
    fun `without a pose it falls back to the default direction and the cast position`() {
        handler.handle(cast(null), params, phases)

        val spawn = projectiles.spawned.single()
        assertThat(spawn.direction).isEqualTo(ProjectileHandler.DEFAULT_DIRECTION)
        assertThat(spawn.origin).isEqualTo(Vec3(10.0, 64.0, 10.0))
        assertThat(spawn.spec.skill!!.worldId).isNull()
    }

    @Test
    fun `zero launch speed and lifetime still build a valid spec`() {
        handler.handle(cast(null), params.copy(launchSpeed = 0.0, maxLifetimeTicks = 0), phases)

        val spec = projectiles.spawned.single().spec
        assertThat((spec.motion as MotionProfile.Linear).initialSpeed).isEqualTo(ProjectileHandler.MIN_SPEED)
        assertThat(spec.lifetime).isEqualTo(LifetimePolicy.Ticks(ProjectileHandler.DEFAULT_LIFETIME_TICKS))
    }
}
