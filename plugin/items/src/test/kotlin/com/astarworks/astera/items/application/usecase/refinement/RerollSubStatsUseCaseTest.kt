package com.astarworks.astera.items.application.usecase.refinement

import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.event.ItemInstanceRerolled
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.service.refinement.InMemoryItemInstanceRepository
import com.astarworks.astera.items.application.service.refinement.RefinementProfileRegistry
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId
import com.astarworks.astera.items.domain.refinement.RefinementError
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.astarworks.astera.items.domain.refinement.SubStatRoll
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import com.astarworks.astera.items.testfixtures.refinement.FakeRefinementRandomizer
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RerollSubStatsUseCaseTest {

    private val itemId = ItemId("example-sword")
    private val owner = PlayerId(UUID.randomUUID())

    private fun walletWith(amount: Long): Wallet {
        val container = SimpleAttributeContainer()
        val wallet = Wallet(container)
        wallet.deposit(Currency.GOLD, amount)
        return wallet
    }

    private fun seededInstance(initial: List<SubStatRoll>): ItemInstance =
        ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = itemId,
            owner = owner,
            createdAt = Instant.EPOCH,
            subStatRolls = initial,
        )

    @Test
    fun `reroll rejects another player's instance before charging or rolling`() {
        val registry = InMemoryItemRegistry().apply { register(itemSpec(id = itemId.value)) }
        val instances = InMemoryItemInstanceRepository()
        val rolls = listOf(SubStatRoll(SubStatKey.ATTACK, 30.0, 1))
        val instance = seededInstance(rolls)
        instances.save(instance)
        val bus = FakeDomainEventBus()
        val useCase = RerollSubStatsUseCase(
            registry, instances, FakeRefinementRandomizer(), bus,
            { RefinementSpec(enabled = true, subStatCount = 1, candidateSubStats = listOf(SubStatKey.ATTACK), maxRollsPerStat = 5) },
        )
        val wallet = walletWith(10_000L)
        val result = useCase.reroll(PlayerId(UUID.randomUUID()), wallet, instance.id)

        assertThat(result.errorOrNull()).isEqualTo(RefinementError.UnknownInstance(instance.id))
        assertThat(wallet.balance(Currency.GOLD)).isEqualTo(10_000L)
        assertThat(instance.rerollCount()).isZero()
        assertThat(instance.subStatRolls()).isEqualTo(rolls)
        assertThat(bus.publishedOfType<ItemInstanceRerolled>()).isEmpty()
    }

    @Test
    fun `reroll keeps the better candidate by default and publishes kept=true`() {
        val registry = InMemoryItemRegistry().apply { register(itemSpec(id = itemId.value)) }
        val instances = InMemoryItemInstanceRepository()
        val instance = seededInstance(listOf(SubStatRoll(SubStatKey.ATTACK, 30.0, 1)))
        instances.save(instance)

        val randomizer = FakeRefinementRandomizer()
        randomizer.queueRollSubStats(listOf(SubStatRoll(SubStatKey.ATTACK, 80.0, 1))) // better

        val bus = FakeDomainEventBus()
        val profiles = RefinementProfileRegistry().apply {
            register(
                itemId,
                RefinementSpec(
                    enabled = true,
                    subStatCount = 1,
                    candidateSubStats = listOf(SubStatKey.ATTACK),
                    maxRollsPerStat = 5,
                ),
            )
        }
        val useCase = RerollSubStatsUseCase(
            items = registry,
            instances = instances,
            randomizer = randomizer,
            eventBus = bus,
            refinementSpecResolver = profiles::resolve,
        )

        val wallet = walletWith(10_000L)
        val result = useCase.reroll(owner, wallet, instance.id)
        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrNull()!!
        assertThat(outcome.kept).isTrue()
        assertThat(outcome.candidate.single().magnitude).isEqualTo(80.0)
        assertThat(outcome.previous.single().magnitude).isEqualTo(30.0)
        assertThat(outcome.costPaid).isEqualTo(900L) // first reroll
        // Wallet was charged.
        assertThat(wallet.balance(Currency.GOLD)).isEqualTo(10_000L - 900L)
        // Instance reflects the new roll set.
        val stored = instances.findById(instance.id)!!
        assertThat(stored.subStatRolls().single().magnitude).isEqualTo(80.0)
        // Event with kept=true.
        val events = bus.publishedOfType<ItemInstanceRerolled>()
        assertThat(events).hasSize(1)
        assertThat(events.single().keptResult).isTrue()
        assertThat(events.single().rerollIndex).isEqualTo(0)
    }

    @Test
    fun `reroll discards worse candidate but still pays cost and publishes kept=false`() {
        val registry = InMemoryItemRegistry().apply { register(itemSpec(id = itemId.value)) }
        val instances = InMemoryItemInstanceRepository()
        val instance = seededInstance(listOf(SubStatRoll(SubStatKey.ATTACK, 90.0, 1)))
        instances.save(instance)

        val randomizer = FakeRefinementRandomizer()
        randomizer.queueRollSubStats(listOf(SubStatRoll(SubStatKey.ATTACK, 10.0, 1))) // worse

        val bus = FakeDomainEventBus()
        val profiles = RefinementProfileRegistry().apply {
            register(
                itemId,
                RefinementSpec(
                    enabled = true,
                    subStatCount = 1,
                    candidateSubStats = listOf(SubStatKey.ATTACK),
                    maxRollsPerStat = 5,
                ),
            )
        }
        val useCase = RerollSubStatsUseCase(
            items = registry,
            instances = instances,
            randomizer = randomizer,
            eventBus = bus,
            refinementSpecResolver = profiles::resolve,
        )

        val wallet = walletWith(10_000L)
        val result = useCase.reroll(owner, wallet, instance.id)
        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrNull()!!
        assertThat(outcome.kept).isFalse()
        assertThat(outcome.costPaid).isEqualTo(900L)
        // Wallet was charged.
        assertThat(wallet.balance(Currency.GOLD)).isEqualTo(10_000L - 900L)
        // Instance still carries the old roll.
        val stored = instances.findById(instance.id)!!
        assertThat(stored.subStatRolls().single().magnitude).isEqualTo(90.0)
        // Reroll count bumped.
        assertThat(stored.rerollCount()).isEqualTo(1)
        // Event with kept=false published anyway (analytics signal).
        val events = bus.publishedOfType<ItemInstanceRerolled>()
        assertThat(events).hasSize(1)
        assertThat(events.single().keptResult).isFalse()
    }

    @Test
    fun `reroll fails with InsufficientFunds when wallet cannot cover the cost`() {
        val registry = InMemoryItemRegistry().apply { register(itemSpec(id = itemId.value)) }
        val instances = InMemoryItemInstanceRepository()
        val instance = seededInstance(listOf(SubStatRoll(SubStatKey.ATTACK, 30.0, 1)))
        instances.save(instance)

        val bus = FakeDomainEventBus()
        val profiles = RefinementProfileRegistry().apply {
            register(
                itemId,
                RefinementSpec(
                    enabled = true,
                    subStatCount = 1,
                    candidateSubStats = listOf(SubStatKey.ATTACK),
                    maxRollsPerStat = 5,
                ),
            )
        }
        val useCase = RerollSubStatsUseCase(
            items = registry,
            instances = instances,
            randomizer = FakeRefinementRandomizer(), // not consulted on failure
            eventBus = bus,
            refinementSpecResolver = profiles::resolve,
        )

        val wallet = walletWith(100L) // less than the 900 first-roll cost
        val result = useCase.reroll(owner, wallet, instance.id)
        assertThat(result.isFailure).isTrue()
        val err = result.errorOrNull() as RefinementError.InsufficientFunds
        assertThat(err.tried).isEqualTo(900L)
        assertThat(err.have).isEqualTo(100L)
        assertThat(err.currency).isEqualTo(Currency.GOLD)
        // No event published on the failure path.
        assertThat(bus.publishedOfType<ItemInstanceRerolled>()).isEmpty()
    }

    @Test
    fun `reroll cost escalates with rerollCount and plateaus after 5`() {
        val registry = InMemoryItemRegistry().apply { register(itemSpec(id = itemId.value)) }
        val instances = InMemoryItemInstanceRepository()
        val instance = seededInstance(listOf(SubStatRoll(SubStatKey.ATTACK, 90.0, 1)))
        instances.save(instance)

        val bus = FakeDomainEventBus()
        val profiles = RefinementProfileRegistry().apply {
            register(
                itemId,
                RefinementSpec(
                    enabled = true,
                    subStatCount = 1,
                    candidateSubStats = listOf(SubStatKey.ATTACK),
                    maxRollsPerStat = 99,
                ),
            )
        }
        // Always-discard predicate so we can iterate through cost ladder
        // without changing rolls.
        val discard: (List<SubStatRoll>, List<SubStatRoll>) -> Boolean = { _, _ -> false }
        val useCase = RerollSubStatsUseCase(
            items = registry,
            instances = instances,
            randomizer = FakeRefinementRandomizer().apply {
                repeat(6) { queueRollSubStats(listOf(SubStatRoll(SubStatKey.ATTACK, 10.0, 1))) }
            },
            eventBus = bus,
            refinementSpecResolver = profiles::resolve,
        )

        val wallet = walletWith(100_000L)
        val expectedCosts = longArrayOf(900L, 1000L, 2000L, 2500L, 3000L, 3000L)
        val costs = mutableListOf<Long>()
        repeat(6) {
            val r = useCase.reroll(owner, wallet, instance.id, keepIfBetter = discard).getOrNull()!!
            costs += r.costPaid
        }
        assertThat(costs.toLongArray()).isEqualTo(expectedCosts)
    }
}
