package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class BettingMatchTest {

    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val stake = BetStake(Currency.GOLD, 250L)
    private val policy = BettingPolicy.testing()
    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val matchId = MatchId("bet-1v1-001")

    private fun initiate(): BettingMatch =
        BettingMatch.initiate(BettingMatchId.mint(), policy, challenger, opponent, stake, now)

    @Test
    fun `initiate produces a PendingAcceptance record`() {
        val m = initiate()
        assertThat(m.status).isEqualTo(BettingStatus.PendingAcceptance)
        assertThat(m.challengerId).isEqualTo(challenger)
        assertThat(m.opponentId).isEqualTo(opponent)
        assertThat(m.stake).isEqualTo(stake)
        assertThat(m.acceptedAt).isNull()
        assertThat(m.resolvedAt).isNull()
        assertThat(m.matchId).isNull()
        assertThat(m.acceptanceDeadline).isEqualTo(now.plusMillis(policy.acceptanceTimeout.inWholeMilliseconds))
    }

    @Test
    fun `pot is 2x stake amount`() {
        val m = initiate()
        assertThat(m.pot()).isEqualTo(500L)
    }

    @Test
    fun `accept transitions to Active and records matchId`() {
        val accepted = initiate().accept(now.plusSeconds(5), matchId)
        assertThat(accepted.status).isEqualTo(BettingStatus.Active)
        assertThat(accepted.acceptedAt).isEqualTo(now.plusSeconds(5))
        assertThat(accepted.matchId).isEqualTo(matchId)
    }

    @Test
    fun `accept cannot run twice`() {
        val first = initiate().accept(now.plusSeconds(5), matchId)
        assertThatThrownBy { first.accept(now.plusSeconds(10), matchId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `decline transitions to Declined`() {
        val declined = initiate().decline()
        assertThat(declined.status).isEqualTo(BettingStatus.Declined)
    }

    @Test
    fun `expire transitions to Expired`() {
        val expired = initiate().expire()
        assertThat(expired.status).isEqualTo(BettingStatus.Expired)
    }

    @Test
    fun `cancel from Active transitions to Cancelled`() {
        val cancelled = initiate().accept(now.plusSeconds(5), matchId).cancel()
        assertThat(cancelled.status).isEqualTo(BettingStatus.Cancelled)
    }

    @Test
    fun `cancel from PendingAcceptance rejects`() {
        assertThatThrownBy { initiate().cancel() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `resolve with challenger as winner`() {
        val active = initiate().accept(now.plusSeconds(5), matchId)
        val resolved = active.resolve(challenger, BettingEndReason.MatchOutcome, now.plusSeconds(60))
        assertThat(resolved.status).isEqualTo(BettingStatus.Resolved)
        assertThat(resolved.winnerId).isEqualTo(challenger)
        assertThat(resolved.endReason).isEqualTo(BettingEndReason.MatchOutcome)
        assertThat(resolved.resolvedAt).isEqualTo(now.plusSeconds(60))
    }

    @Test
    fun `resolve as draw allows null winner`() {
        val active = initiate().accept(now.plusSeconds(5), matchId)
        val resolved = active.resolve(null, BettingEndReason.Draw, now.plusSeconds(60))
        assertThat(resolved.winnerId).isNull()
        assertThat(resolved.endReason).isEqualTo(BettingEndReason.Draw)
    }

    @Test
    fun `resolve rejects unknown winnerId`() {
        val stranger = PlayerId(UUID.randomUUID())
        val active = initiate().accept(now.plusSeconds(5), matchId)
        assertThatThrownBy { active.resolve(stranger, BettingEndReason.MatchOutcome, now.plusSeconds(60)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("winnerId")
    }

    @Test
    fun `resolve rejects from PendingAcceptance`() {
        assertThatThrownBy { initiate().resolve(challenger, BettingEndReason.MatchOutcome, now) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `initiate rejects self-betting`() {
        assertThatThrownBy {
            BettingMatch.initiate(BettingMatchId.mint(), policy, challenger, challenger, stake, now)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("distinct")
    }
}

internal class BettingStatusTest {

    @Test
    fun `terminal states report themselves`() {
        assertThat(BettingStatus.Declined.isTerminal).isTrue()
        assertThat(BettingStatus.Expired.isTerminal).isTrue()
        assertThat(BettingStatus.Cancelled.isTerminal).isTrue()
        assertThat(BettingStatus.Resolved.isTerminal).isTrue()
    }

    @Test
    fun `non-terminal states report themselves`() {
        assertThat(BettingStatus.PendingAcceptance.isTerminal).isFalse()
        assertThat(BettingStatus.Active.isTerminal).isFalse()
    }
}
