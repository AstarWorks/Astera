package com.astarworks.astera.economy.application.eventbus.listeners.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.BettingMatchResolved
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingStatus
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class ResolveBettingOnMatchEndedListenerTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = BettingPolicy.testing()
    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val matchId = MatchId("bet-resolve-test")
    private val redTeam = TeamId("red")
    private val blueTeam = TeamId("blue")

    private val bettingRepo = InMemoryBettingMatchRepository()
    private val matchRepo = InMemoryMatchRepository()
    private val eventBus = FakeDomainEventBus()

    private val listener = ResolveBettingOnMatchEndedListener(bettingRepo, matchRepo, eventBus, clock)

    @BeforeEach
    fun setUp() {
        val active = BettingMatch.initiate(
            id = BettingMatchId.mint(),
            policy = policy,
            challengerId = challenger,
            opponentId = opponent,
            stake = BetStake(Currency.GOLD, 100L),
            now = now.minusSeconds(60),
        ).accept(now.minusSeconds(30), matchId)
        bettingRepo.save(active)
        matchRepo.save(
            Match(
                id = matchId,
                phase = MatchPhase.ACTIVE,
                teams = listOf(
                    Team(redTeam, MessageKey("astera.team.red"), MessageKey("astera.color.red"), setOf(challenger)),
                    Team(blueTeam, MessageKey("astera.team.blue"), MessageKey("astera.color.blue"), setOf(opponent)),
                ),
                score = Score(emptyMap()),
                stageId = StageId("stage-bet"),
                startedAtTick = 0L,
            ),
        )
    }

    @Test
    fun `winning team challenger publishes resolved with challenger as winner`() {
        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = redTeam,
                atTick = 100L,
                reason = EndReason.ScoreThresholdReached,
            ),
        )
        val betting = bettingRepo.findByMatchId(matchId)!!
        assertThat(betting.status).isEqualTo(BettingStatus.Resolved)
        assertThat(betting.winnerId).isEqualTo(challenger)
        val resolved = eventBus.published.filterIsInstance<BettingMatchResolved>()
        assertThat(resolved).hasSize(1)
        assertThat(resolved.single().winnerId).isEqualTo(challenger)
        assertThat(resolved.single().endReasonKind).isEqualTo("match_outcome")
    }

    @Test
    fun `null winning team publishes draw`() {
        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = null,
                atTick = 100L,
                reason = EndReason.TimerExpired,
            ),
        )
        val resolved = eventBus.published.filterIsInstance<BettingMatchResolved>().single()
        assertThat(resolved.winnerId).isNull()
        assertThat(resolved.endReasonKind).isEqualTo("draw")
    }

    @Test
    fun `admin ended publishes admin_cancelled`() {
        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = null,
                atTick = 100L,
                reason = EndReason.AdminEnded,
            ),
        )
        val resolved = eventBus.published.filterIsInstance<BettingMatchResolved>().single()
        assertThat(resolved.endReasonKind).isEqualTo("admin_cancelled")
        assertThat(resolved.winnerId).isNull()
    }

    @Test
    fun `cancelled with reason publishes cancelled_with_reason`() {
        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = null,
                atTick = 100L,
                reason = EndReason.Cancelled("server-restart"),
            ),
        )
        val resolved = eventBus.published.filterIsInstance<BettingMatchResolved>().single()
        assertThat(resolved.endReasonKind).isEqualTo("cancelled_with_reason")
    }

    @Test
    fun `match for non-betting match-id is ignored`() {
        val otherMatchId = MatchId("other-match")
        listener.handle(
            MatchEnded(otherMatchId, redTeam, 100L, EndReason.ScoreThresholdReached),
        )
        // No BettingMatchResolved should be published.
        assertThat(eventBus.published.filterIsInstance<BettingMatchResolved>()).isEmpty()
    }
}
