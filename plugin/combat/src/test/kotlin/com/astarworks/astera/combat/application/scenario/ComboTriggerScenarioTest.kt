package com.astarworks.astera.combat.application.scenario

import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.service.combo.InMemoryComboTracker
import com.astarworks.astera.combat.application.service.damagebonus.ComboBonusSource
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.combo.ComboPattern
import com.astarworks.astera.combat.domain.combo.ComboRoute
import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.ComboRouteCompleted
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeKnockbackGateway
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario A.
 *
 * Proves: a player whose combo just completed a [ComboRoute] sees their
 * **next** [DamageAttempt] amplified by `bonusDamageMultiplier`, and the
 * bonus is single-use (the strike after that returns to base damage).
 *
 * Pre-Wave K, `ComboRouteCompleted` fired but no subscriber consumed the
 * `bonusDamageMultiplier`, so the chain finisher visually played but the
 * follow-up strike dealt base damage. This regression test pins the wire.
 */
class ComboTriggerScenarioTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())
    private val weapon = ItemId("scimitar-of-thunder")
    private val finisher = SkillId("thunder-cleave")

    @Test
    fun `combo route completion amplifies the next damage attempt then disarms`() {
        // Arrange — tracker + bonus source + minimal damage pipeline ----------
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 0L)
        val tracker = InMemoryComboTracker(bus)
        val route = ComboRoute(
            id = ComboRouteId("scimitar-of-thunder-finisher"),
            weaponId = weapon,
            pattern = ComboPattern.HitCount(target = 3),
            finisher = finisher,
            bonusDamageMultiplier = 2.0,
        )
        tracker.registerRoute(route)
        val comboSource = ComboBonusSource(bus)

        val rule = DamageRule(
            elementMatrix = IElementMatrix.Neutral,
            modifiers = emptyList(),
            friendlyFire = FriendlyFirePolicy.Allow,
            randomizer = FakeDamageRandomizer(listOf(0.99)),
        )
        val gateway = FakeDamageGateway().apply {
            nextOutcome = DamageOutcome.HpReduced(remainingHp = 100.0)
        }
        val useCase = ApplyDamageUseCase(
            damageGateway = gateway,
            knockbackGateway = FakeKnockbackGateway(),
            statusContainer = FakeStatusEffectContainer(),
            damageRule = rule,
            armorProvider = FakeArmorEquipmentProvider(),
            teamLookup = null,
            eventBus = bus,
            scheduler = scheduler,
            bonusSources = listOf(comboSource),
        )

        // Act 1 — drive the chain to completion via the tracker -----------------
        tracker.recordHit(attacker, weapon, victim, damageDealt = 1.0, atTick = 0L)
        tracker.recordHit(attacker, weapon, victim, damageDealt = 1.0, atTick = 1L)
        tracker.recordHit(attacker, weapon, victim, damageDealt = 1.0, atTick = 2L)

        // Sanity — the tracker fired the route and the source recorded the arm
        val completedEvents = bus.publishedOfType<ComboRouteCompleted>()
        assertThat(completedEvents).hasSize(1)
        assertThat(completedEvents[0].bonusDamageMultiplier).isEqualTo(2.0)

        // Act 2 — next damage strike should be amplified ------------------------
        scheduler.advanceTicks(3)
        val first = useCase.execute(weaponAttempt(baseDamage = 10.0))
        assertThat(first.isSuccess).isTrue()
        val firstDealt = bus.publishedOfType<DamageDealt>().single()
        assertThat(firstDealt.resolved.finalAmount).isEqualTo(20.0) // 10 × 2.0

        // Act 3 — second strike consumes nothing; base damage returns ----------
        val second = useCase.execute(weaponAttempt(baseDamage = 10.0))
        assertThat(second.isSuccess).isTrue()
        val dealtEvents = bus.publishedOfType<DamageDealt>()
        assertThat(dealtEvents).hasSize(2)
        assertThat(dealtEvents[1].resolved.finalAmount).isEqualTo(10.0)
    }

    private fun weaponAttempt(baseDamage: Double): DamageAttempt = DamageAttempt(
        targetId = victim,
        baseProfile = DamageProfile.single(baseDamage, DamageAttribute.PHYSICAL),
        source = DamageSource.Item(
            itemId = weapon,
            attackerId = attacker,
            attackerPosition = null,
        ),
        headshot = false,
    )
}
