package com.astarworks.astera.match.application.eventbus.listeners.lobby

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5wireup (ADR-0116 §G5) — TeleportToLobbyOnMatchEnded test.
 */
class TeleportToLobbyOnMatchEndedListenerTest {

    private val matchId = MatchId("end-test-match")
    private val p1 = PlayerId(UUID.randomUUID())
    private val p2 = PlayerId(UUID.randomUUID())

    @Test
    fun `handle teleports all participants and publishes PlayerReturnedToLobby for each`() {
        val matches = InMemoryMatchRepository().apply {
            save(
                Match(
                    id = matchId,
                    phase = MatchPhase.ACTIVE,
                    teams = listOf(
                        Team(TeamId("red"), MessageKey("a"), MessageKey("a"), setOf(p1)),
                        Team(TeamId("blue"), MessageKey("a"), MessageKey("a"), setOf(p2)),
                    ),
                    score = Score(mapOf(TeamId("red") to 0, TeamId("blue") to 0)),
                    stageId = StageId("any"),
                    startedAtTick = 0L,
                ),
            )
        }
        val world = FakeWorldGateway()
        val bus = FakeDomainEventBus()
        val listener = TeleportToLobbyOnMatchEndedListener(
            matches = matches,
            worldGateway = world,
            eventBus = bus,
            scheduler = FakeScheduler(),
        )

        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = TeamId("red"),
                atTick = 100L,
                reason = EndReason.TimerExpired,
            ),
        )

        // FakeWorldGateway.lobbySpawn always returns Success.
        val returned = bus.publishedOfType<PlayerReturnedToLobby>().map { it.playerId }
        assertThat(returned).containsExactlyInAnyOrder(p1, p2)
    }

    @Test
    fun `handle no-ops when match is not in the repository`() {
        val matches = InMemoryMatchRepository()
        val world = FakeWorldGateway()
        val bus = FakeDomainEventBus()
        val listener = TeleportToLobbyOnMatchEndedListener(matches, world, bus, FakeScheduler())

        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = null,
                atTick = 0L,
                reason = EndReason.TimerExpired,
            ),
        )

        assertThat(bus.publishedOfType<PlayerReturnedToLobby>()).isEmpty()
    }
}
