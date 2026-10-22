package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.service.NoOpKnockbackGateway
import com.astarworks.astera.combat.application.service.StubCelEvaluator
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.animation.Easing
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

class AoeHandlerTest {
    @Test
    fun `area query is scoped to the cast world`() {
        val caster = PlayerId(UUID.randomUUID())
        val local = PlayerId(UUID.randomUUID())
        val otherWorld = PlayerId(UUID.randomUUID())
        val arena = WorldId("arena")
        val query = object : INearbyTargetQuery {
            override fun findInRadius(center: Vec3, radius: Double, world: WorldId?) =
                if (world == arena) listOf(local) else listOf(local, otherWorld)
        }
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val gateway = FakeDamageGateway()
        val damage = ApplyDamageUseCase(
            gateway, NoOpKnockbackGateway(), FakeStatusEffectContainer(),
            DamageRule(IElementMatrix.Neutral, emptyList(), FriendlyFirePolicy.Allow, FakeDamageRandomizer(listOf(0.99))),
            FakeArmorEquipmentProvider(), null, bus, scheduler,
        )
        val dispatcher = MechanicDispatcher(
            damage, null, FakeWorldGateway(), null, query, null, StubCelEvaluator(), scheduler, bus,
        )
        AoeHandler(query, damage, dispatcher).handle(
            SkillCast(caster, SkillId("nova"), Vec3.ZERO, 0L, PlayerPose.standing(arena, Vec3.ZERO, Vec3(0.0, 0.0, 1.0))),
            SkillParams.AoeParams(5.0, DamageProfile.single(10.0, DamageAttribute.WATER), Easing.LINEAR),
            PhaseMechanics.Aoe(),
        )
        assertThat(gateway.applied.map { it.first }).containsExactly(local)
    }
}
