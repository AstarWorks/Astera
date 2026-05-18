package com.astarworks.astera.match.application.usecase.generator

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
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

class SpawnResourceUseCaseTest {

    @Test
    fun `default amount published when no multiplier present`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 100L)
        val useCase = SpawnResourceUseCase(bus, scheduler)
        val generator = neutralGenerator(amount = 1)

        useCase.spawn(generator)

        val events = bus.publishedOfType<ResourceSpawned>()
        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.generatorId).isEqualTo(generator.id)
        assertThat(event.matchId).isEqualTo(generator.matchId)
        assertThat(event.position).isEqualTo(generator.position)
        assertThat(event.currency).isEqualTo(Currency.STAR)
        assertThat(event.amount).isEqualTo(1)
        assertThat(event.atTick).isEqualTo(100L)
    }

    @Test
    fun `amount multiplier 2x doubles the published amount`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val useCase = SpawnResourceUseCase(bus, scheduler)
        val generator = neutralGenerator(amount = 3)
        // Seed identity (1.0) so the MULTIPLY modifier has a non-zero base to
        // compound against — modifier-only attributes degrade to fallback 1.0
        // when the base is absent (see AdvanceGeneratorChargeUseCase contract).
        generator.attributes.set(
            GeneratorAttributes.AMOUNT_MULTIPLIER,
            AttributeValue.NumberValue(1.0),
        )
        generator.attributes.addModifier(
            AttributeModifier(
                key = GeneratorAttributes.AMOUNT_MULTIPLIER,
                operation = AttributeModifier.Operation.MULTIPLY,
                value = 2.0,
                sourceId = "anomaly:resource-spike",
            ),
        )

        useCase.spawn(generator)

        val event = bus.publishedOfType<ResourceSpawned>().single()
        assertThat(event.amount).isEqualTo(6)
    }

    @Test
    fun `amount coerced to at least 1 even with very small multiplier`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val useCase = SpawnResourceUseCase(bus, scheduler)
        val generator = neutralGenerator(amount = 1)
        generator.attributes.set(
            GeneratorAttributes.AMOUNT_MULTIPLIER,
            AttributeValue.NumberValue(1.0),
        )
        generator.attributes.addModifier(
            AttributeModifier(
                key = GeneratorAttributes.AMOUNT_MULTIPLIER,
                operation = AttributeModifier.Operation.MULTIPLY,
                value = 0.1,
                sourceId = "anomaly:resource-drought",
            ),
        )

        useCase.spawn(generator)

        val event = bus.publishedOfType<ResourceSpawned>().single()
        assertThat(event.amount).isEqualTo(1)
    }

    private fun neutralGenerator(amount: Int): StarGenerator = StarGenerator(
        id = GeneratorId("arena-test"),
        matchId = MatchId("match-x1"),
        position = Vec3(0.0, 64.0, 0.0),
        currency = Currency.STAR,
        period = 30.seconds,
        amount = amount,
        tier = GeneratorTier.Iron,
        affiliation = Affiliation.Neutral,
        attributes = SimpleAttributeContainer(),
    )
}
