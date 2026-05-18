package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.usecase.anomaly.ResolveAnomalyEffectUseCase
import com.astarworks.astera.match.application.usecase.anomaly.TriggerAnomalyUseCase
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.event.AnomalyTriggered
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.GeneratorTier
import com.astarworks.astera.match.domain.generator.StarGenerator
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Wave 5c [AnomalyScheduler] end-to-end tests.
 *
 * Drives the full pipeline with a [FakeScheduler] + [FakeDomainEventBus] +
 * [InMemoryMatchRepository] + [InMemoryGeneratorRepository] +
 * [InMemoryAnomalyScheduleRepository], using a deterministic [Random] whose
 * `nextDouble()` always returns 0.0 so anomalies fire every period.
 *
 * Verifies:
 *  - at least one [AnomalyTriggered] published after advancing N periods.
 *  - the modifier installed by ResourceSpike is present on every generator
 *    in the match.
 *  - after the descriptor's duration elapses, the modifier is removed.
 */
internal class AnomalySchedulerTest {

    private val matchId = MatchId("anomaly-int1")

    @Test
    fun `at least one AnomalyTriggered fires after several periods`() {
        val (bus, scheduler, _, _, _, handle) = bootstrap()

        // Advance well past the default 100-tick period; with FIRE_PROBABILITY=0.1
        // and our stub Random returning 0.0, every period fires.
        scheduler.advanceTicks(1000L)

        val triggered = bus.publishedOfType<AnomalyTriggered>()
        assertThat(triggered).isNotEmpty
        assertThat(triggered.first().matchId).isEqualTo(matchId)
        assertThat(triggered.first().type).isEqualTo(AnomalyType.ResourceSpike)

        handle.cancel()
    }

    @Test
    fun `ResourceSpike installs a MULTIPLY modifier on every generator in the match`() {
        val (_, scheduler, generators, _, _, handle) = bootstrap()

        // One period is enough to fire once with the deterministic Random.
        scheduler.advanceTicks(100L)

        for (gen in generators.listForMatch(matchId)) {
            val anomalyMods = gen.attributes.modifiers().filter { it.sourceId.startsWith("anomaly:") }
            assertThat(anomalyMods).isNotEmpty
            anomalyMods.forEach { m ->
                assertThat(m.key).isEqualTo(GeneratorAttributes.PERIOD_MULTIPLIER)
                assertThat(m.operation).isEqualTo(AttributeModifier.Operation.MULTIPLY)
                assertThat(m.value).isEqualTo(0.5)
            }
        }

        handle.cancel()
    }

    @Test
    fun `modifier is removed once the descriptor's duration elapses`() {
        val (_, scheduler, generators, _, _, handle) = bootstrap()

        // Fire one anomaly at the first period (tick 100).
        scheduler.advanceTicks(100L)
        // Cancel the repeating task so no further anomalies fire while we
        // wait out the duration.
        handle.cancel()

        // Confirm modifiers are present immediately after the fire.
        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers().any { it.sourceId.startsWith("anomaly:") }).isTrue()
        }

        // Descriptor durationTicks = 600 in our wiring; advance past it.
        scheduler.advanceTicks(601L)

        for (gen in generators.listForMatch(matchId)) {
            val anomalyMods = gen.attributes.modifiers().filter { it.sourceId.startsWith("anomaly:") }
            assertThat(anomalyMods).isEmpty()
        }
    }

    @Test
    fun `no descriptors registered for a match means no anomalies fire`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val generators = InMemoryGeneratorRepository()
        val matches = InMemoryMatchRepository().apply { save(activeMatch()) }
        val anomalies = InMemoryAnomalyScheduleRepository() // empty
        val trigger = TriggerAnomalyUseCase(bus, scheduler)
        val resolve = ResolveAnomalyEffectUseCase(generators)
        val sched = AnomalyScheduler(
            repo = anomalies,
            trigger = trigger,
            resolve = resolve,
            scheduler = scheduler,
            matches = matches,
            random = alwaysFireRandom(),
        )

        val handle = sched.start()
        scheduler.advanceTicks(1000L)

        assertThat(bus.publishedOfType<AnomalyTriggered>()).isEmpty()
        handle.cancel()
    }

    private data class Wiring(
        val bus: FakeDomainEventBus,
        val scheduler: FakeScheduler,
        val generators: InMemoryGeneratorRepository,
        val matches: InMemoryMatchRepository,
        val anomalies: InMemoryAnomalyScheduleRepository,
        val handle: com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle,
    )

    private fun bootstrap(): Wiring {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()

        val generators = InMemoryGeneratorRepository().apply {
            save(makeGenerator("gen-a"))
            save(makeGenerator("gen-b"))
        }
        val matches = InMemoryMatchRepository().apply { save(activeMatch()) }

        val descriptor = AnomalyDescriptor(
            type = AnomalyType.ResourceSpike,
            weight = 5,
            durationTicks = 600L,
            attributeKey = GeneratorAttributes.PERIOD_MULTIPLIER,
            modifierOperation = AttributeModifier.Operation.MULTIPLY,
            modifierValue = 0.5,
        )
        val anomalies = InMemoryAnomalyScheduleRepository().apply {
            setForMatch(matchId, listOf(descriptor))
        }

        val trigger = TriggerAnomalyUseCase(bus, scheduler)
        val resolve = ResolveAnomalyEffectUseCase(generators)
        val anomalyScheduler = AnomalyScheduler(
            repo = anomalies,
            trigger = trigger,
            resolve = resolve,
            scheduler = scheduler,
            matches = matches,
            random = alwaysFireRandom(),
        )
        val handle = anomalyScheduler.start()
        return Wiring(bus, scheduler, generators, matches, anomalies, handle)
    }

    private fun makeGenerator(id: String): StarGenerator = StarGenerator(
        id = GeneratorId(id),
        matchId = matchId,
        position = Vec3(0.0, 64.0, 0.0),
        currency = Currency.STAR,
        period = 30.seconds,
        amount = 1,
        tier = GeneratorTier.Iron,
        affiliation = Affiliation.Neutral,
        attributes = SimpleAttributeContainer(),
    )

    private fun activeMatch(): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(
                id = TeamId("red"),
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = emptySet(),
            ),
        ),
        score = Score.EMPTY,
        stageId = StageId("example-arena"),
        startedAtTick = 0L,
    )

    /**
     * Deterministic [Random] subclass whose `nextDouble()` always returns 0.0
     * (below `FIRE_PROBABILITY`) and whose `nextInt(bound)` always returns 0
     * (picks the first weighted descriptor). All other methods delegate to a
     * fixed-seed `Random` so test traces stay reproducible.
     */
    private fun alwaysFireRandom(): Random = object : Random() {
        private val delegate = Random(seed = 1234L)
        override fun nextBits(bitCount: Int): Int = delegate.nextBits(bitCount)
        override fun nextDouble(): Double = 0.0
        override fun nextInt(until: Int): Int = 0
    }
}
