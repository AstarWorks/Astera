package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.OnHitEffect
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSkillBinding
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectRegistry
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakePlayerPoseLookup
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import com.astarworks.astera.testfixtures.effect.FakeParticleScheduler
import com.astarworks.astera.testfixtures.effect.FakeSoundScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ProjectileImpactResolverTest {

    private val world = WorldId("world")
    private val caster = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())
    private val skillId = SkillId("example-blast")
    private val castOrigin = Vec3(1.0, 64.0, 2.0)
    private val impact = Vec3(20.0, 65.0, 2.0)

    private val scheduler = FakeScheduler()
    private val bus = FakeDomainEventBus()
    private val damageGateway = FakeDamageGateway()
    private val statusContainer = FakeStatusEffectContainer()
    private val worldGateway = FakeWorldGateway()
    private val particles = FakeParticleScheduler()
    private val sounds = FakeSoundScheduler()
    private val poses = FakePlayerPoseLookup().apply {
        place(victim, Vec3(20.0, 64.0, 2.0), world = world)
    }

    private val applyDamage = ApplyDamageUseCase(
        damageGateway = damageGateway,
        knockbackGateway = NoOpKnockbackGateway(),
        statusContainer = statusContainer,
        damageRule = DamageRule(
            elementMatrix = object : com.astarworks.astera.combat.domain.rule.IElementMatrix {
                override fun multiplier(attacker: DamageAttribute, defender: DamageAttribute): Double = 1.0
            },
            modifiers = emptyList(),
            friendlyFire = FriendlyFirePolicy.Allow,
            randomizer = FakeDamageRandomizer(listOf(0.99)),
        ),
        armorProvider = StubArmorEquipmentProvider(),
        teamLookup = null,
        eventBus = bus,
        scheduler = scheduler,
    )
    private val applyStatus = ApplyStatusUseCase(
        effectRegistry = FakeStatusEffectRegistry(),
        effectContainer = statusContainer,
        eventBus = bus,
    )
    private val dispatcher = MechanicDispatcher(
        applyDamage = applyDamage,
        applyStatus = applyStatus,
        worldGateway = worldGateway,
        knockbackGateway = NoOpKnockbackGateway(),
        nearbyQuery = FakeNearbyTargetQuery(),
        broadcaster = null,
        celEvaluator = StubCelEvaluator(),
        scheduler = scheduler,
        eventBus = bus,
        particleScheduler = particles,
        soundScheduler = sounds,
        poseLookup = poses,
    )
    private val resolver = ProjectileImpactResolver(
        dispatcher = dispatcher,
        applyDamage = applyDamage,
        applyStatus = applyStatus,
        scheduler = scheduler,
        particleScheduler = particles,
        soundScheduler = sounds,
    )

    private val damage = Mechanic.Damage(
        targets = Targeter.Target,
        components = listOf(DamageComponent(NumericValue.Literal(8.0), DamageAttribute.FIRE)),
    )
    private val hitParticle = Mechanic.SpawnParticle(
        targets = Targeter.HitLocation,
        particleId = ParticleId("example-burst"),
    )
    private val missSound = Mechanic.PlaySound(
        targets = Targeter.Caster,
        soundId = SoundId("entity.player.attack.sweep"),
    )

    private fun flight(phases: PhaseMechanics.Projectile, onHit: List<OnHitEffect> = emptyList()) =
        ProjectileImpactResolver.Flight(
            id = ProjectileId.random(),
            owner = caster,
            spec = ProjectileSpec(
                motion = MotionProfile.Linear(36.0),
                hit = HitProfile.Sphere(0.5),
                lifetime = LifetimePolicy.Ticks(60),
                onHit = onHit,
                skill = ProjectileSkillBinding(
                    skillId = skillId,
                    phases = phases,
                    castOrigin = castOrigin,
                    worldId = world,
                    pose = PlayerPose.standing(world, castOrigin, Vec3(1.0, 0.0, 0.0)),
                ),
            ),
        )

    @Test
    fun `entity hit runs hit_entity phase against the struck player`() {
        val f = flight(PhaseMechanics.Projectile(hitEntity = listOf(damage, hitParticle), miss = listOf(missSound)))

        resolver.onHitEntity(f, victim, impact)

        assertThat(damageGateway.applied).hasSize(1)
        assertThat(damageGateway.applied[0].first).isEqualTo(victim)
        assertThat(damageGateway.applied[0].third).isInstanceOf(DamageSource.Skill::class.java)
        // hit_location particle lands at the impact point, in the cast world.
        val emit = particles.scheduled.single().spec as ScheduledParticleSpec.EmitOnce
        assertThat(emit.position).isEqualTo(impact)
        assertThat(emit.world).isEqualTo(world)
        // an entity hit is not a miss
        assertThat(sounds.scheduled).isEmpty()
    }

    @Test
    fun `block hit runs hit_block then miss, with caster sound anchored at cast origin`() {
        val f = flight(PhaseMechanics.Projectile(hitBlock = listOf(hitParticle), miss = listOf(missSound)))

        resolver.onHitBlock(f, impact)

        assertThat((particles.scheduled.single().spec as ScheduledParticleSpec.EmitOnce).position).isEqualTo(impact)
        val sound = sounds.scheduled.single().spec as ScheduledSoundSpec.EmitOnce
        assertThat(sound.position).isEqualTo(castOrigin)
        assertThat(sound.world).isEqualTo(world)
        assertThat(damageGateway.applied).isEmpty()
    }

    @Test
    fun `expire runs expire then miss at the expiry point`() {
        val expireParticle = Mechanic.SpawnParticle(
            targets = Targeter.ProjectilePosition,
            particleId = ParticleId("fizzle"),
        )
        val f = flight(PhaseMechanics.Projectile(expire = listOf(expireParticle), miss = listOf(missSound)))

        resolver.onExpire(f, impact)

        assertThat((particles.scheduled.single().spec as ScheduledParticleSpec.EmitOnce).position).isEqualTo(impact)
        assertThat(sounds.scheduled).hasSize(1)
    }

    @Test
    fun `spec onHit damage effect applies to a struck player and is skipped on block hits`() {
        val profile = com.astarworks.astera.core.domain.damage.DamageProfile.single(4.0, DamageAttribute.PHYSICAL)
        val f = flight(PhaseMechanics.Projectile(), onHit = listOf(OnHitEffect.DealDamage(profile)))

        resolver.onHitBlock(f, impact)
        assertThat(damageGateway.applied).isEmpty()

        resolver.onHitEntity(f, victim, impact)
        assertThat(damageGateway.applied).hasSize(1)
        assertThat(damageGateway.applied[0].third).isInstanceOf(DamageSource.Projectile::class.java)
    }

    @Test
    fun `non-player entity hit still runs the phase but finds no target`() {
        val f = flight(PhaseMechanics.Projectile(hitEntity = listOf(damage, hitParticle)))

        resolver.onHitEntity(f, null, impact)

        assertThat(damageGateway.applied).isEmpty()
        assertThat(particles.scheduled).hasSize(1)
    }
}
