package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.service.InMemoryEntityDamageGateway
import com.astarworks.astera.combat.application.service.training.TrainingDummy
import com.astarworks.astera.combat.application.service.training.TrainingDummyEntityDamageGateway
import com.astarworks.astera.combat.application.service.training.TrainingDummyRegistry
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.rule.FriendlyFirePolicy
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.combat.testfixtures.FakeArmorEquipmentProvider
import com.astarworks.astera.combat.testfixtures.FakeDamageGateway
import com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer
import com.astarworks.astera.combat.testfixtures.FakeKnockbackGateway
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.lobby.TrainingDummyKind
import com.astarworks.astera.core.domain.lobby.TrainingDummySpot
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeMatchTeamLookup
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0237: the damage source stamps the [CombatContext] on every `DamageDealt` / `PlayerKilled`. */
class ApplyDamageUseCaseContextTest {

    private val red = PlayerId(UUID.randomUUID())
    private val blue = PlayerId(UUID.randomUUID())
    private val bystander = PlayerId(UUID.randomUUID())
    private val matchId = MatchId("m-1")

    private val gateway = FakeDamageGateway()
    private val lookup = FakeMatchTeamLookup().also {
        it.place(red, TeamId("red"), matchId)
        it.place(blue, TeamId("blue"), matchId)
    }
    private val bus = FakeDomainEventBus()
    private val registry = TrainingDummyRegistry()
    private val mobs = InMemoryEntityDamageGateway()
    private val dummy = TrainingDummy(TrainingDummySpot("range-10", TrainingDummyKind.RANGE, 10.5, 65.0, 0.5, 90f, distance = 10), maxHp = 100.0)
    private val dummyBody = PlayerId(UUID.randomUUID())
    private val mob = PlayerId(UUID.randomUUID())

    private val useCase: ApplyDamageUseCase

    init {
        registry.register(dummy)
        registry.bind(dummy, dummyBody)
        mobs.spawn(mob, 50.0)
        useCase = ApplyDamageUseCase(
            damageGateway = gateway,
            knockbackGateway = FakeKnockbackGateway(),
            statusContainer = FakeStatusEffectContainer(),
            damageRule = DamageRule(
                elementMatrix = IElementMatrix.Neutral,
                modifiers = emptyList(),
                friendlyFire = FriendlyFirePolicy.Allow,
                randomizer = FakeDamageRandomizer(listOf(0.99)),
            ),
            armorProvider = FakeArmorEquipmentProvider(),
            teamLookup = lookup,
            eventBus = bus,
            scheduler = FakeScheduler(),
            entityDamageGateway = TrainingDummyEntityDamageGateway(mobs, registry, now = { 0L }) { _, _, _ -> },
        )
    }

    private fun hit(attacker: PlayerId?, target: PlayerId, amount: Double = 10.0) = useCase.execute(
        DamageAttempt(
            targetId = target,
            baseProfile = DamageProfile.single(amount, DamageAttribute.PHYSICAL),
            source = attacker?.let { DamageSource.Item(ItemId("test-sword"), it) } ?: DamageSource.Environment.Generic,
            headshot = false,
        ),
    )

    @Test
    fun `a hit on a training dummy is Training, however many times it is hit`() {
        repeat(30) { hit(red, dummyBody, 50.0) }

        val events = bus.publishedOfType<DamageDealt>()
        assertThat(events).hasSize(30)
        assertThat(events.map { it.context }.toSet()).containsExactly(CombatContext.Training)
        assertThat(dummy.hp).isEqualTo(TrainingDummy.MIN_HP)
        assertThat(bus.publishedOfType<PlayerKilled>()).isEmpty()
    }

    @Test
    fun `a hit on a mob is World for a lobby attacker and Match for a participant`() {
        hit(bystander, mob)
        hit(red, mob)

        assertThat(bus.publishedOfType<DamageDealt>().map { it.context })
            .containsExactly(CombatContext.World, CombatContext.Match(matchId))
    }

    @Test
    fun `a hit between match participants is a Match hit and its kill is a Match kill`() {
        gateway.nextOutcome = DamageOutcome.Killed

        hit(red, blue)

        assertThat(bus.publishedOfType<DamageDealt>().single().context).isEqualTo(CombatContext.Match(matchId))
        assertThat(bus.publishedOfType<PlayerKilled>().single().context).isEqualTo(CombatContext.Match(matchId))
    }

    @Test
    fun `a lobby player hitting or killing a participant, or another lobby player, is Lobby`() {
        gateway.nextOutcome = DamageOutcome.Killed

        hit(bystander, blue)
        hit(red, bystander)

        assertThat(bus.publishedOfType<DamageDealt>().map { it.context }).containsOnly(CombatContext.Lobby)
        assertThat(bus.publishedOfType<PlayerKilled>().map { it.context }).containsOnly(CombatContext.Lobby)
    }
}
