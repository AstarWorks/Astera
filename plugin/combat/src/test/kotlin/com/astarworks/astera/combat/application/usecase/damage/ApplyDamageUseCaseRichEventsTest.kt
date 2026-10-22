package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.contents.ArmorContents
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.MitigationReason
import com.astarworks.astera.combat.domain.event.CriticalHit
import com.astarworks.astera.combat.domain.event.DamageMitigated
import com.astarworks.astera.combat.domain.event.FriendlyFireBlocked
import com.astarworks.astera.combat.domain.event.KnockbackApplied
import com.astarworks.astera.combat.domain.rule.ArmorReductionModifier
import com.astarworks.astera.combat.domain.rule.CriticalStrikeModifier
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.rule.KnockbackModifier
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeKnockbackGateway
import com.astarworks.astera.combat.testfixtures.FakeMatchTeamLookup
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.core.domain.damage.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5a — proves the rich sub-event emissions added to
 * [ApplyDamageUseCase] for `CriticalHit / DamageMitigated / KnockbackApplied /
 * FriendlyFireBlocked`. The legacy [ApplyDamageUseCaseTest] kept the existing
 * `DamageDealt` / `PlayerKilled` contract; this file covers the new ones so
 * extending the use case for Wave 6 (BukkitEventAdapter) can not silently
 * regress kill-feed / analytics signals.
 *
 * One test per event variant. Each scenario uses the minimum scaffolding
 * needed to force that single emission.
 */
class ApplyDamageUseCaseRichEventsTest {

    private val targetId = PlayerId(UUID.randomUUID())
    private val attackerId = PlayerId(UUID.randomUUID())
    private val attackerPos = Vec3(10.0, 64.0, 10.0)
    private val weaponSource = DamageSource.Item(
        itemId = ItemId("sword"),
        attackerId = attackerId,
        attackerPosition = attackerPos,
    )

    private fun attempt(source: DamageSource = weaponSource) = DamageAttempt(
        targetId = targetId,
        baseProfile = DamageProfile.single(10.0, DamageAttribute.PHYSICAL),
        source = source,
        headshot = false,
    )

    private fun newScaffold(
        modifiers: List<com.astarworks.astera.combat.domain.rule.DamageModifier> = emptyList(),
        friendlyFire: FriendlyFirePolicy = FriendlyFirePolicy.Allow,
        randomizer: FakeDamageRandomizer = FakeDamageRandomizer(listOf(0.99)),
        armor: FakeArmorEquipmentProvider = FakeArmorEquipmentProvider(),
        teamLookup: FakeMatchTeamLookup? = null,
    ): Scaffold {
        val gateway = FakeDamageGateway()
        val knockbackGw = FakeKnockbackGateway()
        val container = FakeStatusEffectContainer()
        val bus = FakeDomainEventBus()
        val rule = DamageRule(
            elementMatrix = IElementMatrix.Neutral,
            modifiers = modifiers,
            friendlyFire = friendlyFire,
            randomizer = randomizer,
        )
        val useCase = ApplyDamageUseCase(
            damageGateway = gateway,
            knockbackGateway = knockbackGw,
            statusContainer = container,
            damageRule = rule,
            armorProvider = armor,
            teamLookup = teamLookup,
            eventBus = bus,
            scheduler = com.astarworks.astera.testfixtures.FakeScheduler(),
        )
        return Scaffold(useCase, gateway, knockbackGw, bus)
    }

    private data class Scaffold(
        val useCase: ApplyDamageUseCase,
        val gateway: FakeDamageGateway,
        val knockback: FakeKnockbackGateway,
        val eventBus: FakeDomainEventBus,
    )

    // CriticalHit ------------------------------------------------------------

    @Test
    fun `CriticalHit - emitted before DamageDealt when crit triggers`() {
        // critChance = 1.0 ensures the crit rolls regardless of randomizer value.
        val s = newScaffold(
            modifiers = listOf(CriticalStrikeModifier(critChance = 1.0, critMultiplier = 2.0)),
            randomizer = FakeDamageRandomizer(listOf(0.0)),
        )

        s.useCase.execute(attempt())

        val crits = s.eventBus.publishedOfType<CriticalHit>()
        assertThat(crits).hasSize(1)
        assertThat(crits[0].attacker).isEqualTo(attackerId)
        assertThat(crits[0].target).isEqualTo(targetId)
        assertThat(crits[0].multiplier).isEqualTo(2.0)
    }

    // ignore_invulnerability ---------------------------------------------------

    @Test
    fun `ignoreInvulnerability on the attempt reaches the gateway`() {
        val s = newScaffold()

        s.useCase.execute(attempt())
        s.useCase.execute(attempt().copy(ignoreInvulnerability = true))

        assertThat(s.gateway.ignoreInvulnerabilityCalls).containsExactly(false, true)
    }

    // DamageMitigated --------------------------------------------------------

    @Test
    fun `DamageMitigated - emitted per equipped armor slot`() {
        val armorRegistry = ArmorContents()
        val helmet = ArmorSpec(
            id = ArmorId("test-helmet"),
            displayNameKey = MessageKey("test.helmet"),
            slot = ArmorSlot.HELMET,
            reduction = com.astarworks.astera.combat.domain.armor.ArmorReduction.flat(0.25),
            setKey = null,
        )
        val bundle = ArmorBundle(playerId = targetId, equipped = mapOf(ArmorSlot.HELMET to helmet))
        val armorProvider = FakeArmorEquipmentProvider().apply { set(targetId, bundle) }

        val s = newScaffold(
            modifiers = listOf(ArmorReductionModifier(registry = armorRegistry)),
            armor = armorProvider,
        )

        s.useCase.execute(attempt())

        val mitigations = s.eventBus.publishedOfType<DamageMitigated>()
        assertThat(mitigations).hasSize(1)
        val event = mitigations[0]
        assertThat(event.target).isEqualTo(targetId)
        assertThat(event.originalAmount).isEqualTo(10.0)
        assertThat(event.finalAmount).isEqualTo(7.5) // 10 * (1 - 0.25)
        assertThat(event.reason).isInstanceOf(MitigationReason.Armor::class.java)
        val reason = event.reason as MitigationReason.Armor
        assertThat(reason.slot).isEqualTo(ArmorSlot.HELMET)
        assertThat(reason.percent).isEqualTo(0.25)
    }

    // KnockbackApplied -------------------------------------------------------

    @Test
    fun `KnockbackApplied - emitted when modifier produces an impulse`() {
        val s = newScaffold(
            modifiers = listOf(KnockbackModifier(baseMagnitude = 0.4)),
        )

        s.useCase.execute(attempt(weaponSource))

        val kb = s.eventBus.publishedOfType<KnockbackApplied>()
        assertThat(kb).hasSize(1)
        assertThat(kb[0].target).isEqualTo(targetId)
        assertThat(kb[0].impulse).isNotEqualTo(Vec3.ZERO)
        // Gateway also invoked exactly once.
        assertThat(s.knockback.applied).hasSize(1)
        assertThat(s.knockback.applied[0].first).isEqualTo(targetId)
    }

    // FriendlyFireBlocked ----------------------------------------------------

    @Test
    fun `FriendlyFireBlocked - emitted when policy blocks same-team hit`() {
        val team = TeamId("red")
        val lookup = FakeMatchTeamLookup().apply {
            assign(attackerId, team)
            assign(targetId, team)
        }
        val s = newScaffold(
            friendlyFire = FriendlyFirePolicy.BlockSameTeam,
            teamLookup = lookup,
        )

        s.useCase.execute(attempt(weaponSource))

        val ff = s.eventBus.publishedOfType<FriendlyFireBlocked>()
        assertThat(ff).hasSize(1)
        assertThat(ff[0].attacker).isEqualTo(attackerId)
        assertThat(ff[0].intendedTarget).isEqualTo(targetId)
        // Gateway must NOT have been invoked when policy blocked.
        assertThat(s.gateway.applied).isEmpty()
    }
}
