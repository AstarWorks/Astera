package com.astarworks.astera.match.application.service

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
import com.astarworks.astera.match.application.usecase.generator.AdvanceGeneratorChargeUseCase
import com.astarworks.astera.match.application.usecase.generator.SpawnResourceUseCase
import com.astarworks.astera.match.domain.event.ResourceSpawned
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.GeneratorTier
import com.astarworks.astera.match.domain.generator.StarGenerator
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class StarGeneratorSchedulerTest {

    private val matchId = MatchId("match-int1")

    @Test
    fun `advancing 600 ticks fires exactly one ResourceSpawned for a 30s generator`() {
        val (bus, scheduler, _, handle) = bootstrap()

        scheduler.advanceTicks(600L)

        val spawned = bus.publishedOfType<ResourceSpawned>()
        assertThat(spawned).hasSize(1)
        val event = spawned.single()
        assertThat(event.currency).isEqualTo(Currency.STAR)
        assertThat(event.amount).isEqualTo(1)
        assertThat(event.matchId).isEqualTo(matchId)

        handle.cancel()
    }

    @Test
    fun `advancing 1200 ticks fires two ResourceSpawned events`() {
        val (bus, scheduler, _, handle) = bootstrap()

        scheduler.advanceTicks(1200L)

        val spawned = bus.publishedOfType<ResourceSpawned>()
        assertThat(spawned).hasSize(2)
        spawned.forEach { event ->
            assertThat(event.currency).isEqualTo(Currency.STAR)
            assertThat(event.amount).isEqualTo(1)
        }

        handle.cancel()
    }

    @Test
    fun `match with no generators is skipped without error`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val generators = InMemoryGeneratorRepository()
        val matches = InMemoryMatchRepository().apply { save(activeMatch()) }
        val spawn = SpawnResourceUseCase(bus, scheduler)
        val advance = AdvanceGeneratorChargeUseCase(generators, bus, scheduler, spawn)
        val scoped = StarGeneratorScheduler(generators, advance, spawn, scheduler, bus, matches)
        val handle = scoped.start()

        scheduler.advanceTicks(600L)

        assertThat(bus.publishedOfType<ResourceSpawned>()).isEmpty()
        handle.cancel()
    }

    /** Wire one generator (30s / STAR / amount=1) inside one active match. */
    private data class Wiring(
        val bus: FakeDomainEventBus,
        val scheduler: FakeScheduler,
        val generators: InMemoryGeneratorRepository,
        val handle: com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle,
    )

    private fun bootstrap(): Wiring {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val generators = InMemoryGeneratorRepository().apply {
            save(
                StarGenerator(
                    id = GeneratorId("arena-center"),
                    matchId = matchId,
                    position = Vec3(0.0, 64.0, 0.0),
                    currency = Currency.STAR,
                    period = 30.seconds,
                    amount = 1,
                    tier = GeneratorTier.Iron,
                    affiliation = Affiliation.Neutral,
                    attributes = SimpleAttributeContainer(),
                ),
            )
        }
        val matches = InMemoryMatchRepository().apply { save(activeMatch()) }
        val spawn = SpawnResourceUseCase(bus, scheduler)
        val advance = AdvanceGeneratorChargeUseCase(generators, bus, scheduler, spawn)
        val sgScheduler = StarGeneratorScheduler(
            generators = generators,
            advance = advance,
            spawn = spawn,
            scheduler = scheduler,
            eventBus = bus,
            matches = matches,
        )
        val handle = sgScheduler.start()
        return Wiring(bus, scheduler, generators, handle)
    }

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
}
