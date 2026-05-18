package com.astarworks.astera.match.application.usecase.generator

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.attribute.number
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.service.InMemoryGeneratorRepository
import com.astarworks.astera.match.domain.event.GeneratorChargeAdvanced
import com.astarworks.astera.match.domain.event.ResourceSpawned
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.GeneratorTier
import com.astarworks.astera.match.domain.generator.StarGenerator
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AdvanceGeneratorChargeUseCaseTest {

    private val matchId = MatchId("match-x1")

    @Test
    fun `single tick advances energy without crossing threshold`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val repo = InMemoryGeneratorRepository()
        val spawn = SpawnResourceUseCase(bus, scheduler)
        val advance = AdvanceGeneratorChargeUseCase(repo, bus, scheduler, spawn)

        // 30s period = 600 ticks; advancing 1 tick must not spawn.
        repo.save(neutralGenerator(period = 30.seconds))

        advance.advance(matchId, deltaTicks = 1L)

        assertThat(bus.publishedOfType<ResourceSpawned>()).isEmpty()
        val charge = bus.publishedOfType<GeneratorChargeAdvanced>().single()
        assertThat(charge.newEnergyTicks).isEqualTo(1.0)
    }

    @Test
    fun `energy crossing threshold publishes ResourceSpawned and carries overshoot`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val repo = InMemoryGeneratorRepository()
        val spawn = SpawnResourceUseCase(bus, scheduler)
        val advance = AdvanceGeneratorChargeUseCase(repo, bus, scheduler, spawn)

        val generator = neutralGenerator(period = 30.seconds)
        repo.save(generator)
        // Seed energy just below the 600-tick threshold so a single tick crosses it.
        generator.attributes.set(
            GeneratorAttributes.ENERGY,
            AttributeValue.NumberValue(599.0),
        )

        advance.advance(matchId, deltaTicks = 1L)

        // ResourceSpawned fires with currency=STAR amount=1.
        val spawned = bus.publishedOfType<ResourceSpawned>().single()
        assertThat(spawned.currency).isEqualTo(Currency.STAR)
        assertThat(spawned.amount).isEqualTo(1)

        // Energy is reset to overshoot (600 - 600 = 0).
        val stored = repo.findById(generator.id)!!
            .attributes.number(GeneratorAttributes.ENERGY)
        assertThat(stored).isEqualTo(0.0)
    }

    @Test
    fun `period multiplier 0_5 halves the ticks needed to spawn`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val repo = InMemoryGeneratorRepository()
        val spawn = SpawnResourceUseCase(bus, scheduler)
        val advance = AdvanceGeneratorChargeUseCase(repo, bus, scheduler, spawn)

        // 30s base period (600 ticks). Sudden Death overlays MULTIPLY 0.5 →
        // effective threshold = 300 ticks. The base PERIOD_MULTIPLIER must
        // be seeded to 1.0 (identity) so the modifier has a non-zero base to
        // compound against — this is the contract callers follow when adding
        // modifier-only attributes.
        val generator = neutralGenerator(period = 30.seconds)
        generator.attributes.set(
            GeneratorAttributes.PERIOD_MULTIPLIER,
            AttributeValue.NumberValue(1.0),
        )
        generator.attributes.addModifier(
            AttributeModifier(
                key = GeneratorAttributes.PERIOD_MULTIPLIER,
                operation = AttributeModifier.Operation.MULTIPLY,
                value = 0.5,
                sourceId = "sudden-death:match-x1",
            ),
        )
        repo.save(generator)
        // Seed energy just below the effective threshold of 300.
        generator.attributes.set(
            GeneratorAttributes.ENERGY,
            AttributeValue.NumberValue(299.0),
        )

        advance.advance(matchId, deltaTicks = 1L)

        assertThat(bus.publishedOfType<ResourceSpawned>()).hasSize(1)
    }

    @Test
    fun `period multiplier 0_5 does not yet spawn before half-threshold`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val repo = InMemoryGeneratorRepository()
        val spawn = SpawnResourceUseCase(bus, scheduler)
        val advance = AdvanceGeneratorChargeUseCase(repo, bus, scheduler, spawn)

        val generator = neutralGenerator(period = 30.seconds)
        generator.attributes.set(
            GeneratorAttributes.PERIOD_MULTIPLIER,
            AttributeValue.NumberValue(1.0),
        )
        generator.attributes.addModifier(
            AttributeModifier(
                key = GeneratorAttributes.PERIOD_MULTIPLIER,
                operation = AttributeModifier.Operation.MULTIPLY,
                value = 0.5,
                sourceId = "sudden-death:match-x1",
            ),
        )
        repo.save(generator)
        // Seed energy below the half-threshold: 298 + 1 = 299 < 300.
        generator.attributes.set(
            GeneratorAttributes.ENERGY,
            AttributeValue.NumberValue(298.0),
        )

        advance.advance(matchId, deltaTicks = 1L)

        assertThat(bus.publishedOfType<ResourceSpawned>()).isEmpty()
    }

    @Test
    fun `delta zero does nothing`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val repo = InMemoryGeneratorRepository()
        val spawn = SpawnResourceUseCase(bus, scheduler)
        val advance = AdvanceGeneratorChargeUseCase(repo, bus, scheduler, spawn)
        repo.save(neutralGenerator(period = 30.seconds))

        advance.advance(matchId, deltaTicks = 0L)

        assertThat(bus.published).isEmpty()
    }

    private fun neutralGenerator(period: kotlin.time.Duration): StarGenerator = StarGenerator(
        id = GeneratorId("arena-test"),
        matchId = matchId,
        position = Vec3.ZERO,
        currency = Currency.STAR,
        period = period,
        amount = 1,
        tier = GeneratorTier.Iron,
        affiliation = Affiliation.Neutral,
        attributes = SimpleAttributeContainer(),
    )
}
