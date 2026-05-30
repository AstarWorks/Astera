package com.astarworks.astera.combat.application.scenario

import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.service.damagebonus.TargetLockBonusSource
import com.astarworks.astera.combat.application.service.targetlock.InMemoryTargetLockController
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ProjectileOrigin
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.targetlock.TargetLockConfig
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeKnockbackGateway
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario C.
 *
 * Proves: after the [InMemoryTargetLockController] has acquired a lock
 * and that lock has been bound to a victim `PlayerId` via
 * [InMemoryTargetLockController.armAgainstPlayer], the **next** ranged
 * [DamageAttempt] is amplified by `TargetLockConfig.damageBonusMultiplier`
 * — and is single-use (the strike after returns to base damage).
 */
class TargetLockScenarioTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())
    private val bow = ItemId("locking-bow")

    @Test
    fun `armed target lock amplifies the next ranged hit and disarms`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 0L)
        val cfg = TargetLockConfig(
            acquisitionTicks = 2,
            lockLifetimeTicks = 200,
            damageBonusMultiplier = 1.8,
        ) // other fields use defaults
        val controller = InMemoryTargetLockController(bus, initialConfig = cfg)

        // Acquire a lock against an EntityHandle stub, then bind it to the
        // victim PlayerId. (Wave 6b's Paper adapter would do the bind
        // automatically; Phase 1 PoC tests do it explicitly.)
        val handle = EntityHandle(id = victim.uuid, backendId = "test")
        controller.tickAim(attacker, bow, handle, atTick = 0L)
        controller.tickAim(attacker, bow, handle, atTick = 1L)
        controller.tickAim(attacker, bow, handle, atTick = 2L)
        controller.armAgainstPlayer(attacker, victim, atTick = 2L)

        val source = TargetLockBonusSource(controller)
        val useCase = newPipeline(bus, scheduler, source)

        scheduler.advanceTicks(3)
        val projectileId = ProjectileId(UUID.randomUUID())

        // First ranged hit — bonus applies.
        val first = useCase.execute(
            DamageAttempt(
                targetId = victim,
                baseProfile = DamageProfile.single(10.0, DamageAttribute.PHYSICAL),
                source = DamageSource.Projectile(
                    projectileId = projectileId,
                    ownerId = attacker,
                    origin = ProjectileOrigin.FromItem(bow),
                ),
                headshot = false,
            ),
        )
        assertThat(first.isSuccess).isTrue()
        val firstDealt = bus.publishedOfType<DamageDealt>().last()
        assertThat(firstDealt.resolved.finalAmount).isEqualTo(18.0) // 10 × 1.8

        // Second ranged hit — bonus disarmed, base damage returns.
        val second = useCase.execute(
            DamageAttempt(
                targetId = victim,
                baseProfile = DamageProfile.single(10.0, DamageAttribute.PHYSICAL),
                source = DamageSource.Projectile(
                    projectileId = ProjectileId(UUID.randomUUID()),
                    ownerId = attacker,
                    origin = ProjectileOrigin.FromItem(bow),
                ),
                headshot = false,
            ),
        )
        assertThat(second.isSuccess).isTrue()
        val dealtEvents = bus.publishedOfType<DamageDealt>()
        assertThat(dealtEvents.last().resolved.finalAmount).isEqualTo(10.0)
    }

    @Test
    fun `melee attempt is ignored by the target-lock source`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 0L)
        val cfg = TargetLockConfig(
            acquisitionTicks = 1,
            lockLifetimeTicks = 200,
            damageBonusMultiplier = 1.8,
        )
        val controller = InMemoryTargetLockController(bus, initialConfig = cfg)
        controller.tickAim(attacker, bow, EntityHandle(victim.uuid, "test"), atTick = 0L)
        controller.tickAim(attacker, bow, EntityHandle(victim.uuid, "test"), atTick = 1L)
        controller.armAgainstPlayer(attacker, victim, atTick = 1L)

        val source = TargetLockBonusSource(controller)
        val useCase = newPipeline(bus, scheduler, source)

        scheduler.advanceTicks(2)
        val melee = useCase.execute(
            DamageAttempt(
                targetId = victim,
                baseProfile = DamageProfile.single(10.0, DamageAttribute.PHYSICAL),
                source = DamageSource.Item(itemId = bow, attackerId = attacker),
                headshot = false,
            ),
        )
        assertThat(melee.isSuccess).isTrue()
        val dealt = bus.publishedOfType<DamageDealt>().single()
        // Melee Item attempt — TargetLockBonusSource returns 1.0 for non-projectile.
        assertThat(dealt.resolved.finalAmount).isEqualTo(10.0)
    }

    private fun newPipeline(
        bus: FakeDomainEventBus,
        scheduler: FakeScheduler,
        source: TargetLockBonusSource,
    ): ApplyDamageUseCase {
        val rule = DamageRule(
            elementMatrix = IElementMatrix.Neutral,
            modifiers = emptyList(),
            friendlyFire = FriendlyFirePolicy.Allow,
            randomizer = FakeDamageRandomizer(listOf(0.99)),
        )
        return ApplyDamageUseCase(
            damageGateway = FakeDamageGateway().apply { nextOutcome = DamageOutcome.HpReduced(50.0) },
            knockbackGateway = FakeKnockbackGateway(),
            statusContainer = FakeStatusEffectContainer(),
            damageRule = rule,
            armorProvider = FakeArmorEquipmentProvider(),
            teamLookup = null,
            eventBus = bus,
            scheduler = scheduler,
            bonusSources = listOf(source),
        )
    }
}
