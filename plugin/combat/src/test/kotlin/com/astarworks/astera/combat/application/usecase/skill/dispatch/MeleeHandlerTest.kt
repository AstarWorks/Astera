package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.combat.application.service.InMemoryEntityDamageGateway
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.service.NoOpKnockbackGateway
import com.astarworks.astera.combat.application.service.StubCelEvaluator
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MeleeHandlerTest {

    private val world = WorldId("world")
    private val caster = PlayerId(UUID.randomUUID())
    private val skillId = SkillId("sword-slash")

    private class PositionedQuery(val targets: List<NearbyTarget>) : INearbyTargetQuery {
        override fun findInRadius(center: Vec3, radius: Double, world: WorldId?) = targets.map { it.id }
        override fun findNearby(center: Vec3, radius: Double, world: WorldId?) = targets
    }

    private val playerGateway = FakeDamageGateway()
    private val entityGateway = InMemoryEntityDamageGateway()
    private val eventBus = FakeDomainEventBus()
    private val scheduler = FakeScheduler()

    private fun handler(targets: List<NearbyTarget>): MeleeHandler {
        val query = PositionedQuery(targets)
        val applyDamage = ApplyDamageUseCase(
            damageGateway = playerGateway,
            knockbackGateway = NoOpKnockbackGateway(),
            statusContainer = FakeStatusEffectContainer(),
            damageRule = DamageRule(
                elementMatrix = IElementMatrix.Neutral,
                modifiers = emptyList(),
                friendlyFire = FriendlyFirePolicy.Allow,
                randomizer = FakeDamageRandomizer(listOf(0.99)),
            ),
            armorProvider = FakeArmorEquipmentProvider(),
            teamLookup = null,
            eventBus = eventBus,
            scheduler = scheduler,
            entityDamageGateway = entityGateway,
        )
        val dispatcher = MechanicDispatcher(
            applyDamage = applyDamage,
            applyStatus = null,
            worldGateway = FakeWorldGateway(),
            knockbackGateway = NoOpKnockbackGateway(),
            nearbyQuery = query,
            broadcaster = null,
            celEvaluator = StubCelEvaluator(),
            scheduler = scheduler,
            eventBus = eventBus,
        )
        return MeleeHandler(query, applyDamage, dispatcher)
    }

    private val params = SkillParams.MeleeParams(
        range = 3.5,
        knockback = 0.0,
        damage = DamageProfile.single(8.0, DamageAttribute.PHYSICAL),
        arcDegrees = 100.0,
    )

    private fun cast() = SkillCast(
        caster = caster,
        skillId = skillId,
        at = Vec3(0.0, 0.0, 0.0),
        atTick = 1L,
        pose = PlayerPose.standing(world, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0)),
    )

    private fun at(x: Double, z: Double) = NearbyTarget(PlayerId(UUID.randomUUID()), Vec3(x, 0.0, z))

    @Test
    fun `hits a player in front, skips one behind`() {
        val front = at(0.0, 2.0)
        val behind = at(0.0, -2.0)

        handler(listOf(front, behind)).handle(cast(), params, PhaseMechanics.Melee())

        assertThat(playerGateway.applied.map { it.first }).containsExactly(front.id)
        assertThat(playerGateway.applied[0].second.finalAmount).isEqualTo(8.0)
    }

    @Test
    fun `mob in the cone takes the entity path - damage lands, DamageDealt but no PlayerKilled`() {
        val mob = at(0.5, 2.0)
        entityGateway.spawn(mob.id, hitPoints = 20.0)

        handler(listOf(mob)).handle(cast(), params, PhaseMechanics.Melee())

        assertThat(playerGateway.applied).isEmpty()
        assertThat(entityGateway.hitPointsOf(mob.id)).isEqualTo(12.0)
        assertThat(eventBus.publishedOfType<DamageDealt>().map { it.target }).containsExactly(mob.id)
        assertThat(eventBus.publishedOfType<PlayerKilled>()).isEmpty()
    }

    @Test
    fun `killing a mob publishes no PlayerKilled and a hit on the corpse fails quietly`() {
        val mob = at(0.0, 1.0)
        entityGateway.spawn(mob.id, hitPoints = 5.0)
        val h = handler(listOf(mob))

        h.handle(cast(), params, PhaseMechanics.Melee())
        h.handle(cast(), params, PhaseMechanics.Melee())

        assertThat(entityGateway.hitPointsOf(mob.id)).isNull()
        assertThat(eventBus.publishedOfType<PlayerKilled>()).isEmpty()
        assertThat(eventBus.publishedOfType<DamageDealt>()).hasSize(1)
    }

    @Test
    fun `no target in the cone deals no damage`() {
        handler(listOf(at(0.0, -2.0))).handle(cast(), params, PhaseMechanics.Melee())

        assertThat(playerGateway.applied).isEmpty()
    }

    @Test
    fun `without a cast pose the cone degrades to the sphere around the origin`() {
        val behind = at(0.0, -2.0)
        val noPose = cast().copy(pose = null)

        handler(listOf(behind)).handle(noPose, params, PhaseMechanics.Melee())

        assertThat(playerGateway.applied.map { it.first }).containsExactly(behind.id)
    }

    @Test
    fun `the caster is never a target`() {
        val self = NearbyTarget(caster, Vec3(0.0, 0.0, 1.0))
        handler(listOf(self)).handle(cast(), params, PhaseMechanics.Melee())
        assertThat(playerGateway.applied).isEmpty()
    }
}
