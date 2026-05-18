package com.astarworks.astera.match.application.usecase.world

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.service.InMemoryMatchWorldRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.domain.match.MatchWorld
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wave 5d [DestroyMatchWorldUseCase] tests.
 *
 * Pins:
 *  - happy path: a stored [MatchWorld] is removed when [MatchEnded] fires
 *    for its match id.
 *  - other matches' [MatchWorld]s are not touched.
 *  - `subscribe(...)` actually wires the handler — publishing through the
 *    bus drives the same removal.
 *  - `MatchEnded` for an unknown match id is a no-op.
 */
internal class DestroyMatchWorldUseCaseTest {

    private lateinit var bus: FakeDomainEventBus
    private lateinit var matchWorlds: InMemoryMatchWorldRepository
    private lateinit var useCase: DestroyMatchWorldUseCase

    private val matchA = MatchId("destroy-test-a")
    private val matchB = MatchId("destroy-test-b")
    private val winner = TeamId("blue")

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        matchWorlds = InMemoryMatchWorldRepository()
        useCase = DestroyMatchWorldUseCase(matchWorlds)
    }

    @Test
    fun `handle removes the MatchWorld for the ending match`() {
        matchWorlds.save(
            MatchWorld(matchA, WorldId("world-a"), "match-worlds/${matchA.value}.schem"),
        )

        useCase.handle(
            MatchEnded(
                matchId = matchA,
                winningTeam = winner,
                atTick = 100L,
                reason = EndReason.ScoreThresholdReached,
            ),
        )

        assertThat(matchWorlds.findByMatch(matchA)).isNull()
    }

    @Test
    fun `handle does not touch other matches' MatchWorlds`() {
        val keep = MatchWorld(matchB, WorldId("world-b"), "match-worlds/${matchB.value}.schem")
        matchWorlds.save(
            MatchWorld(matchA, WorldId("world-a"), "match-worlds/${matchA.value}.schem"),
        )
        matchWorlds.save(keep)

        useCase.handle(
            MatchEnded(
                matchId = matchA,
                winningTeam = null,
                atTick = 50L,
                reason = EndReason.TimerExpired,
            ),
        )

        assertThat(matchWorlds.findByMatch(matchA)).isNull()
        assertThat(matchWorlds.findByMatch(matchB)).isEqualTo(keep)
    }

    @Test
    fun `subscribe wires the handler - publishing MatchEnded drives the removal`() {
        matchWorlds.save(
            MatchWorld(matchA, WorldId("world-a"), "match-worlds/${matchA.value}.schem"),
        )

        val subscription = useCase.subscribe(bus)
        bus.publish(
            MatchEnded(
                matchId = matchA,
                winningTeam = winner,
                atTick = 200L,
                reason = EndReason.ObjectiveCompleted,
            ),
        )

        assertThat(subscription.isActive).isTrue()
        assertThat(matchWorlds.findByMatch(matchA)).isNull()
    }

    @Test
    fun `handle for an unknown match id is a no-op`() {
        val keep = MatchWorld(matchB, WorldId("world-b"), "match-worlds/${matchB.value}.schem")
        matchWorlds.save(keep)

        useCase.handle(
            MatchEnded(
                matchId = MatchId("never-saved"),
                winningTeam = null,
                atTick = 10L,
                reason = EndReason.AdminEnded,
            ),
        )

        assertThat(matchWorlds.findByMatch(matchB)).isEqualTo(keep)
    }
}
