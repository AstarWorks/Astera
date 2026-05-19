package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.service.InMemoryRewardBridge
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.EndReason
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
import com.astarworks.astera.match.domain.event.MatchEnded
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RewardOnMatchEndedListenerTest {

    private lateinit var bridge: InMemoryRewardBridge
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var listener: RewardOnMatchEndedListener

    private val matchId = MatchId("match-test")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val redPlayer1 = PlayerId(UUID.randomUUID())
    private val redPlayer2 = PlayerId(UUID.randomUUID())
    private val bluePlayer = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        bridge = InMemoryRewardBridge()
        matches = InMemoryMatchRepository()
        listener = RewardOnMatchEndedListener(matches, bridge)
    }

    @Test
    fun `enqueues bundle for each winning team member`() {
        matches.save(matchWith2Teams())

        listener.handle(MatchEnded(matchId, winningTeam = red, atTick = 100L, reason = EndReason.ScoreThresholdReached))

        val r1 = bridge.peek(redPlayer1)
        val r2 = bridge.peek(redPlayer2)
        val b = bridge.peek(bluePlayer)
        assertThat(r1).hasSize(1)
        assertThat(r2).hasSize(1)
        assertThat(b).isEmpty()
        val bundle = r1.single()
        assertThat(bundle.xp).isEqualTo(RewardOnMatchEndedListener.MATCH_WIN_XP)
        assertThat(bundle.currency).containsEntry(Currency.STAR, RewardOnMatchEndedListener.MATCH_WIN_STAR)
        assertThat(bundle.playerId).isEqualTo(redPlayer1)
        assertThat(bundle.items).isEmpty()
    }

    @Test
    fun `draw - no bundles enqueued`() {
        matches.save(matchWith2Teams())

        listener.handle(MatchEnded(matchId, winningTeam = null, atTick = 100L, reason = EndReason.TimerExpired))

        assertThat(bridge.peek(redPlayer1)).isEmpty()
        assertThat(bridge.peek(redPlayer2)).isEmpty()
        assertThat(bridge.peek(bluePlayer)).isEmpty()
    }

    @Test
    fun `match not found - no bundles enqueued`() {
        listener.handle(MatchEnded(matchId, winningTeam = red, atTick = 100L, reason = EndReason.ScoreThresholdReached))

        assertThat(bridge.peek(redPlayer1)).isEmpty()
    }

    @Test
    fun `winning team not on match - no bundles enqueued`() {
        matches.save(matchWith2Teams())

        listener.handle(MatchEnded(matchId, winningTeam = TeamId("green"), atTick = 100L, reason = EndReason.ScoreThresholdReached))

        assertThat(bridge.peek(redPlayer1)).isEmpty()
        assertThat(bridge.peek(redPlayer2)).isEmpty()
        assertThat(bridge.peek(bluePlayer)).isEmpty()
    }

    private fun matchWith2Teams(): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(
                id = red,
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = setOf(redPlayer1, redPlayer2),
            ),
            Team(
                id = blue,
                displayNameKey = MessageKey("astera.match.team.blue"),
                colorKey = MessageKey("astera.match.team.blue.color"),
                members = setOf(bluePlayer),
            ),
        ),
        score = Score(mapOf(red to 1, blue to 0)),
        stageId = StageId("test-arena"),
        startedAtTick = 0L,
    )
}
