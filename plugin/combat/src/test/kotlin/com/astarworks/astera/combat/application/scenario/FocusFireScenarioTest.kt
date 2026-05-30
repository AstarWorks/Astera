package com.astarworks.astera.combat.application.scenario

import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.service.damagebonus.FocusFireBonusSource
import com.astarworks.astera.combat.application.service.focus.InMemoryFocusFireTracker
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.focus.FocusFireConfig
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
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario B.
 *
 * Proves: when the [InMemoryFocusFireTracker] has recorded `minAttackers`
 * distinct attackers against the same victim within the focus window, the
 * **next** [DamageAttempt] from any of those attackers is amplified by
 * `FocusFireConfig.bonusMultiplier`.
 *
 * Pre-Wave K, `FocusFireState` tracked the bonus but no source consulted
 * it — focus windows turned the HUD chip on but did nothing to damage.
 */
class FocusFireScenarioTest {

    private val victim = PlayerId(UUID.randomUUID())
    private val attackerA = PlayerId(UUID.randomUUID())
    private val attackerB = PlayerId(UUID.randomUUID())
    private val attackerC = PlayerId(UUID.randomUUID())
    private val weapon = ItemId("focus-bow")

    @Test
    fun `three distinct attackers amplify subsequent damage`() {
        val bus = FocusFireBonusSourceFixture.bus()
        val scheduler = FakeScheduler(startTick = 0L)
        val tracker = InMemoryFocusFireTracker(bus).apply {
            configure(FocusFireConfig(minAttackers = 3, windowTicks = 100, bonusMultiplier = 1.5))
        }
        val source = FocusFireBonusSource(tracker)
        val useCase = newPipeline(bus, scheduler, source)

        // Three distinct attackers each land a hit on victim within the window.
        tracker.recordHit(attackerA, victim, damageDealt = 5.0, atTick = 0L)
        tracker.recordHit(attackerB, victim, damageDealt = 5.0, atTick = 1L)
        tracker.recordHit(attackerC, victim, damageDealt = 5.0, atTick = 2L)

        scheduler.advanceTicks(3)

        // Next strike by attackerA — bonus applies (3 attackers in window).
        val result = useCase.execute(
            DamageAttempt(
                targetId = victim,
                baseProfile = DamageProfile.single(20.0, DamageAttribute.PHYSICAL),
                source = DamageSource.Item(itemId = weapon, attackerId = attackerA),
                headshot = false,
            ),
        )
        assertThat(result.isSuccess).isTrue()

        val dealt = bus.publishedOfType<DamageDealt>().single { it.attacker == attackerA }
        assertThat(dealt.resolved.finalAmount).isEqualTo(30.0) // 20 × 1.5
    }

    @Test
    fun `two attackers do not trigger focus`() {
        val bus = FocusFireBonusSourceFixture.bus()
        val scheduler = FakeScheduler(startTick = 0L)
        val tracker = InMemoryFocusFireTracker(bus).apply {
            configure(FocusFireConfig(minAttackers = 3, windowTicks = 100, bonusMultiplier = 1.5))
        }
        val source = FocusFireBonusSource(tracker)
        val useCase = newPipeline(bus, scheduler, source)

        tracker.recordHit(attackerA, victim, damageDealt = 5.0, atTick = 0L)
        tracker.recordHit(attackerB, victim, damageDealt = 5.0, atTick = 1L)

        scheduler.advanceTicks(2)
        val result = useCase.execute(
            DamageAttempt(
                targetId = victim,
                baseProfile = DamageProfile.single(20.0, DamageAttribute.PHYSICAL),
                source = DamageSource.Item(itemId = weapon, attackerId = attackerA),
                headshot = false,
            ),
        )
        assertThat(result.isSuccess).isTrue()
        val dealt = bus.publishedOfType<DamageDealt>().single { it.attacker == attackerA }
        assertThat(dealt.resolved.finalAmount).isEqualTo(20.0) // base, no bonus
    }

    private fun newPipeline(
        bus: FakeDomainEventBus,
        scheduler: FakeScheduler,
        source: FocusFireBonusSource,
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

private object FocusFireBonusSourceFixture {
    fun bus(): FakeDomainEventBus = FakeDomainEventBus()
}
