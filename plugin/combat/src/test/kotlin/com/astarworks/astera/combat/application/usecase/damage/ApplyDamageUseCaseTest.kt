package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.HeadshotModifier
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeKnockbackGateway
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.core.domain.damage.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5a: ApplyDamageUseCase now consumes [DamageRule] + 3 extra ports.
 * These 4 legacy tests are kept (signature update only) to lock the contract
 * preserved across the rewrite. Worker δ adds richer tests covering the new
 * sub-event emissions (CriticalHit / KnockbackApplied / DamageMitigated /
 * FriendlyFireBlocked) in a separate file (or extends this one).
 */
class ApplyDamageUseCaseTest {

    private val targetId = PlayerId(UUID.randomUUID())
    private val attackerId = PlayerId(UUID.randomUUID())

    private lateinit var gateway: FakeDamageGateway
    private lateinit var knockbackGateway: FakeKnockbackGateway
    private lateinit var container: FakeStatusEffectContainer
    private lateinit var armorProvider: FakeArmorEquipmentProvider
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: ApplyDamageUseCase

    @BeforeEach
    fun setUp() {
        gateway = FakeDamageGateway()
        knockbackGateway = FakeKnockbackGateway()
        container = FakeStatusEffectContainer()
        armorProvider = FakeArmorEquipmentProvider()
        eventBus = FakeDomainEventBus()
        val rule = DamageRule(
            elementMatrix = IElementMatrix.Neutral,
            modifiers = listOf(HeadshotModifier()),
            friendlyFire = FriendlyFirePolicy.Allow,
            randomizer = FakeDamageRandomizer(listOf(0.99)), // never crit
        )
        useCase = ApplyDamageUseCase(
            damageGateway = gateway,
            knockbackGateway = knockbackGateway,
            statusContainer = container,
            damageRule = rule,
            armorProvider = armorProvider,
            teamLookup = null,
            eventBus = eventBus,
            scheduler = com.astarworks.astera.testfixtures.FakeScheduler(),
        )
    }

    private fun attempt(source: DamageSource = DamageSource.Environment) = DamageAttempt(
        targetId = targetId,
        baseProfile = DamageProfile(base = 10.0, attribute = DamageAttribute.PHYSICAL),
        source = source,
        headshot = false,
    )

    @Test
    fun `happy path - publishes DamageDealt and returns outcome`() {
        gateway.nextOutcome = DamageOutcome.HpReduced(remainingHp = 5.0)

        val result = useCase.execute(attempt())

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(DamageOutcome.HpReduced(5.0))
        assertThat(gateway.applied).hasSize(1)
        val damageDealt = eventBus.publishedOfType<DamageDealt>()
        assertThat(damageDealt).hasSize(1)
        assertThat(damageDealt[0].target).isEqualTo(targetId)
        assertThat(eventBus.publishedOfType<PlayerKilled>()).isEmpty()
    }

    @Test
    fun `target immune - returns TargetImmune, no gateway call, no DamageDealt published`() {
        val immunitySpec = StatusEffectSpec(
            id = StatusEffectId("immunity"),
            displayNameKey = MessageKey("test.immunity"),
            kind = StatusKind.PARALYSIS,
            magnitude = 0.0,
            stackable = false,
            maxStacks = 1,
        )
        val instance = StatusEffectInstance(
            spec = immunitySpec,
            targetPlayerId = targetId,
            remainingTicks = 100,
            currentStacks = 1,
            appliedAtTick = 0L,
        )
        container.apply(instance)

        val result = useCase.execute(attempt())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(ApplyDamageError.TargetImmune::class.java)
        assertThat(gateway.applied).isEmpty()
        assertThat(eventBus.publishedOfType<DamageDealt>()).isEmpty()
    }

    @Test
    fun `gateway returns Killed outcome - publishes both DamageDealt and PlayerKilled`() {
        gateway.nextOutcome = DamageOutcome.Killed

        val result = useCase.execute(
            attempt(
                source = DamageSource.Item(
                    itemId = ItemId("sword"),
                    attackerId = attackerId,
                ),
            ),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(DamageOutcome.Killed)

        val damageDealt = eventBus.publishedOfType<DamageDealt>()
        assertThat(damageDealt).hasSize(1)
        assertThat(damageDealt[0].attacker).isEqualTo(attackerId)

        val killed = eventBus.publishedOfType<PlayerKilled>()
        assertThat(killed).hasSize(1)
        assertThat(killed[0].victim).isEqualTo(targetId)
        assertThat(killed[0].killer).isEqualTo(attackerId)
    }

    @Test
    fun `gateway returns failure - returns GatewayFailed, no events published`() {
        gateway.nextResult = Result.failure(DamageApplicationError.TargetOffline(targetId))

        val result = useCase.execute(attempt())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(ApplyDamageError.GatewayFailed::class.java)
        assertThat((error as ApplyDamageError.GatewayFailed).cause)
            .isInstanceOf(DamageApplicationError.TargetOffline::class.java)
        assertThat(eventBus.published).isEmpty()
    }
}
