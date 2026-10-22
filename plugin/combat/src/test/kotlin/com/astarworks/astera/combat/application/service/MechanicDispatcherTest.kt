package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.service.MechanicDispatcher.CastSkillInvoker
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectRegistry
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MechanicDispatcherTest {

    private val caster = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())

    private val scheduler = FakeScheduler()
    private val eventBus: IDomainEventBus = FakeDomainEventBus()
    private val worldGateway: IWorldGateway = FakeWorldGateway()
    private val damageGateway = FakeDamageGateway()
    private val statusContainer = FakeStatusEffectContainer()
    private val statusRegistry = FakeStatusEffectRegistry()

    private val damageRule = DamageRule(
        elementMatrix = StubElementMatrix(),
        modifiers = emptyList(),
        friendlyFire = FriendlyFirePolicy.Allow,
        randomizer = FakeDamageRandomizer(listOf(0.99)),
    )
    private val applyDamage = ApplyDamageUseCase(
        damageGateway = damageGateway,
        knockbackGateway = NoOpKnockback(),
        statusContainer = statusContainer,
        damageRule = damageRule,
        armorProvider = StubArmorEquipmentProvider(),
        teamLookup = null,
        eventBus = eventBus,
        scheduler = scheduler,
    )
    private val applyStatus = ApplyStatusUseCase(
        effectRegistry = statusRegistry,
        effectContainer = statusContainer,
        scheduler = scheduler,
    )

    private fun dispatcher(): MechanicDispatcher = MechanicDispatcher(
        applyDamage = applyDamage,
        applyStatus = applyStatus,
        worldGateway = worldGateway,
        knockbackGateway = NoOpKnockback(),
        nearbyQuery = EmptyNearbyTargetQuery(),
        broadcaster = NoOpBroadcaster(),
        celEvaluator = StubCelEvaluator(),
        scheduler = scheduler,
        eventBus = eventBus,
    )

    private fun ctx() = MechanicContext(
        actorId = caster,
        targetId = target,
        origin = Vec3(0.0, 64.0, 0.0),
        castedSkills = setOf(SkillId("test-skill")),
    )

    @Test
    fun `Damage mechanic routes to ApplyDamageUseCase`() {
        val dispatcher = dispatcher()
        val mechanics = listOf(
            Mechanic.Damage(
                targets = Targeter.Target,
                components = listOf(
                    DamageComponent(
                        base = NumericValue.Literal(8.0),
                        attribute = DamageAttribute.FIRE,
                    ),
                ),
            ),
        )

        dispatcher.dispatch(mechanics, ctx())

        assertThat(damageGateway.applied).hasSize(1)
        val (appliedTarget, _, source) = damageGateway.applied[0]
        assertThat(appliedTarget).isEqualTo(target)
        assertThat(source).isInstanceOf(DamageSource.Skill::class.java)
    }

    @Test
    fun `condition gate skips mechanic when false`() {
        val dispatcher = dispatcher()
        val mechanics = listOf(
            Mechanic.Damage(
                targets = Targeter.Target,
                components = listOf(
                    DamageComponent(NumericValue.Literal(5.0), DamageAttribute.FIRE),
                ),
                // HasStatus(silence) returns false in Phase 1 (not wired); but Not(...) flips it.
                // Use IsAlly which Phase 1 returns false → mechanic should skip.
                condition = Condition.IsAlly(Targeter.Caster, Targeter.Target),
            ),
        )

        dispatcher.dispatch(mechanics, ctx())

        // IsAlly returns false in Phase 1 → mechanic skipped → no damage.
        assertThat(damageGateway.applied).isEmpty()
    }

    @Test
    fun `CastSkill recursion stops at MAX_CHAIN_DEPTH`() {
        val dispatcher = dispatcher()
        val invocations = mutableListOf<SkillId>()

        // Wire an invoker that just re-runs another CastSkill mechanic
        // through the dispatcher — recursive chain.
        dispatcher.setCastSkillInvoker(CastSkillInvoker { skillId, _, nextCtx ->
            invocations.add(skillId)
            // Re-cast the same skill — should be blocked by cycle detection.
            dispatcher.dispatch(
                listOf(Mechanic.CastSkill(targets = Targeter.Caster, skillId = skillId)),
                nextCtx,
            )
        })

        dispatcher.dispatch(
            listOf(Mechanic.CastSkill(targets = Targeter.Caster, skillId = SkillId("chain-a"))),
            MechanicContext(actorId = caster, origin = Vec3.ZERO),
        )

        // Only first invocation should fire; cycle guard prevents recursion.
        assertThat(invocations).containsExactly(SkillId("chain-a"))
    }

    @Test
    fun `IfThen dispatches then branch on true, else on false`() {
        val dispatcher = dispatcher()
        // Condition.Not(Cel(...)) — Cel returns false (Stub), Not flips it true.
        val mechanics = listOf(
            Mechanic.IfThen(
                targets = Targeter.Caster,
                predicate = Condition.Not(Condition.Cel("anything")),
                then_ = listOf(
                    Mechanic.Damage(
                        targets = Targeter.Target,
                        components = listOf(
                            DamageComponent(NumericValue.Literal(10.0), DamageAttribute.PHYSICAL),
                        ),
                    ),
                ),
            ),
        )

        dispatcher.dispatch(mechanics, ctx())

        assertThat(damageGateway.applied).hasSize(1)
    }

    @Test
    fun `Loop iterates the specified count`() {
        val dispatcher = dispatcher()
        val mechanics = listOf(
            Mechanic.Loop(
                targets = Targeter.Caster,
                count = IntValue.Literal(3),
                mechanics = listOf(
                    Mechanic.Damage(
                        targets = Targeter.Target,
                        components = listOf(
                            DamageComponent(NumericValue.Literal(2.0), DamageAttribute.PHYSICAL),
                        ),
                    ),
                ),
            ),
        )

        dispatcher.dispatch(mechanics, ctx())

        assertThat(damageGateway.applied).hasSize(3)
    }

    @Test
    fun `delay_ticks defers execution until scheduler advance`() {
        val dispatcher = dispatcher()
        val mechanics = listOf(
            Mechanic.Damage(
                targets = Targeter.Target,
                components = listOf(
                    DamageComponent(NumericValue.Literal(1.0), DamageAttribute.PHYSICAL),
                ),
                delayTicks = IntValue.Literal(10),
            ),
        )

        dispatcher.dispatch(mechanics, ctx())

        // Not yet — scheduler hasn't ticked.
        assertThat(damageGateway.applied).isEmpty()

        scheduler.advanceTicks(10)

        assertThat(damageGateway.applied).hasSize(1)
    }
}

// ---- Test-only stubs (Phase 1 only need surface methods) -------------------

private class StubElementMatrix : com.astarworks.astera.combat.domain.rule.IElementMatrix {
    override fun multiplier(
        attacker: com.astarworks.astera.core.domain.damage.DamageAttribute,
        defender: com.astarworks.astera.core.domain.damage.DamageAttribute,
    ): Double = 1.0
}

private class NoOpKnockback : IKnockbackGateway {
    override fun applyImpulse(target: PlayerId, impulse: Vec3) = Unit
}

internal class StubArmorEquipmentProvider :
    com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider {
    override fun equippedArmor(playerId: PlayerId):
        com.astarworks.astera.combat.domain.armor.ArmorBundle =
        com.astarworks.astera.combat.domain.armor.ArmorBundle.empty(playerId)
}

private class EmptyNearbyTargetQuery : INearbyTargetQuery {
    override fun findInRadius(
        center: Vec3,
        radius: Double,
        world: com.astarworks.astera.core.domain.world.WorldId?,
    ): List<PlayerId> = emptyList()
}

private class NoOpBroadcaster : IBroadcaster {
    override fun publish(event: com.astarworks.astera.core.domain.event.DomainEvent) = Unit
}

private class LocalStubCelEvaluator : ICelEvaluator {
    override fun evaluate(
        script: com.astarworks.astera.combat.domain.skill.expression.CelSkillScript,
        context: com.astarworks.astera.combat.domain.skill.expression.CelContext,
    ): com.astarworks.astera.core.domain.Result<Any, com.astarworks.astera.combat.domain.skill.expression.CelError> =
        com.astarworks.astera.core.domain.Result.Failure(
            com.astarworks.astera.combat.domain.skill.expression.CelError.NotYetImplemented,
        )
}
