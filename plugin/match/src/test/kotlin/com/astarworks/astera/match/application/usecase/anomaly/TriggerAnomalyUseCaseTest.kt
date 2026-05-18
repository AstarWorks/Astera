package com.astarworks.astera.match.application.usecase.anomaly

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.event.AnomalyTriggered
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wave 5c [TriggerAnomalyUseCase] tests.
 *
 * Pins:
 *  - exactly one [AnomalyTriggered] event published per `trigger` call.
 *  - the event carries the [AnomalyDescriptor.type] passed in.
 *  - the returned [com.astarworks.astera.match.domain.anomaly.AnomalyId] is
 *    the same one carried by the event (so [ResolveAnomalyEffectUseCase] can
 *    tag modifiers with `anomaly:<id>` and have [expire] match later).
 *  - `atTick` reflects the scheduler's current tick at trigger time.
 */
internal class TriggerAnomalyUseCaseTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: TriggerAnomalyUseCase

    private val matchId = MatchId("trigger-test-match")
    private val descriptor = AnomalyDescriptor(
        type = AnomalyType.ResourceSpike,
        weight = 5,
        durationTicks = 600L,
        attributeKey = AttributeKey("generator.period_multiplier"),
        modifierOperation = AttributeModifier.Operation.MULTIPLY,
        modifierValue = 0.5,
    )

    @BeforeEach
    fun setUp() {
        eventBus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 42L)
        useCase = TriggerAnomalyUseCase(eventBus, scheduler)
    }

    @Test
    fun `trigger publishes exactly one AnomalyTriggered event`() {
        useCase.trigger(matchId, descriptor)

        assertThat(eventBus.publishedOfType<AnomalyTriggered>()).hasSize(1)
    }

    @Test
    fun `published event carries the descriptor's type and durationTicks`() {
        useCase.trigger(matchId, descriptor)

        val event = eventBus.publishedOfType<AnomalyTriggered>().single()
        assertThat(event.type).isEqualTo(AnomalyType.ResourceSpike)
        assertThat(event.durationTicks).isEqualTo(600L)
        assertThat(event.matchId).isEqualTo(matchId)
    }

    @Test
    fun `returned AnomalyId is the same one carried by the event`() {
        val returned = useCase.trigger(matchId, descriptor)

        val event = eventBus.publishedOfType<AnomalyTriggered>().single()
        assertThat(event.anomalyId).isEqualTo(returned)
    }

    @Test
    fun `atTick reflects the scheduler's current tick`() {
        useCase.trigger(matchId, descriptor)

        val event = eventBus.publishedOfType<AnomalyTriggered>().single()
        assertThat(event.atTick).isEqualTo(42L)
    }

    @Test
    fun `successive triggers mint distinct AnomalyIds`() {
        val a = useCase.trigger(matchId, descriptor)
        val b = useCase.trigger(matchId, descriptor)

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `each non-ResourceSpike type round-trips through the event`() {
        val types = listOf(
            AnomalyType.StatusBurst,
            AnomalyType.SpawnSurge,
            AnomalyType.TimeWarp,
            AnomalyType.GravityShift,
            AnomalyType.FogOfWar,
        )

        for (type in types) {
            val d = descriptor.copy(type = type)
            useCase.trigger(matchId, d)
        }

        val published = eventBus.publishedOfType<AnomalyTriggered>()
        assertThat(published.map { it.type }).containsExactlyElementsOf(types)
    }
}
