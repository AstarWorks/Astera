package com.astarworks.astera.economy.application.eventbus.listeners.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.BettingMatchResolved
import com.astarworks.astera.core.domain.event.BettingPayoutDistributed
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingEscrowService
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingHistoryService
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingMatchRepository
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingPolicyRegistry
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class DistributeBettingPayoutOnResolvedListenerTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = BettingPolicy.testing(lossStreakLimit = 3)
    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val stake = BetStake(Currency.GOLD, 100L)
    private val matchId = MatchId("bet-payout-test")

    private val bettingRepo = InMemoryBettingMatchRepository()
    private val ledger = InMemoryCurrencyLedger()
    private val escrow = InMemoryBettingEscrowService(ledger)
    private val history = InMemoryBettingHistoryService(bettingRepo, policy.cooldownAfterMatch.inWholeMilliseconds)
    private val policies = InMemoryBettingPolicyRegistry(listOf(policy))
    private val eventBus = FakeDomainEventBus()

    private val listener = DistributeBettingPayoutOnResolvedListener(
        bettingRepo, escrow, history, policies, ledger, eventBus, clock,
    )

    private lateinit var bettingMatch: BettingMatch

    @BeforeEach
    fun setUp() {
        ledger.credit(challenger, Currency.GOLD, 500L)
        ledger.credit(opponent, Currency.GOLD, 500L)

        bettingMatch = BettingMatch.initiate(
            BettingMatchId.mint(), policy, challenger, opponent, stake, now.minusSeconds(60),
        ).accept(now.minusSeconds(30), matchId)
        bettingRepo.save(bettingMatch)
        // Pre-escrow both stakes (mirrors AcceptBettingChallengeUseCase).
        escrow.hold(bettingMatch.id, challenger, opponent, Currency.GOLD, stake.amount, now.minusSeconds(30))
        // Post-escrow balances: 400 each.

        // Mark resolved on the repo.
        bettingRepo.save(
            bettingMatch.resolve(
                challenger,
                com.astarworks.astera.economy.domain.betting.BettingEndReason.MatchOutcome,
                now,
            ),
        )
    }

    @Test
    fun `winner payout credits winner with 2x stake (split = 1)`() {
        listener.handle(
            BettingMatchResolved(
                bettingMatchUuid = bettingMatch.id.value,
                matchId = matchId,
                challengerId = challenger,
                opponentId = opponent,
                winnerId = challenger,
                endReasonKind = "match_outcome",
            ),
        )
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(600L)
        assertThat(ledger.balance(opponent, Currency.GOLD)).isEqualTo(400L)
        val payout = eventBus.published.filterIsInstance<BettingPayoutDistributed>().single()
        assertThat(payout.outcomeKind).isEqualTo("winner_paid")
        assertThat(payout.winnerId).isEqualTo(challenger)
        assertThat(payout.winnerPayout).isEqualTo(200L)
        assertThat(payout.refundEach).isEqualTo(0L)
    }

    @Test
    fun `draw refunds both sides`() {
        // Reset the repo's status to Active so we can re-resolve as draw.
        bettingRepo.save(bettingMatch) // back to Active
        bettingRepo.save(
            bettingMatch.resolve(
                null,
                com.astarworks.astera.economy.domain.betting.BettingEndReason.Draw,
                now,
            ),
        )
        listener.handle(
            BettingMatchResolved(
                bettingMatchUuid = bettingMatch.id.value,
                matchId = matchId,
                challengerId = challenger,
                opponentId = opponent,
                winnerId = null,
                endReasonKind = "draw",
            ),
        )
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(500L)
        assertThat(ledger.balance(opponent, Currency.GOLD)).isEqualTo(500L)
        val payout = eventBus.published.filterIsInstance<BettingPayoutDistributed>().single()
        assertThat(payout.outcomeKind).isEqualTo("refunded_both")
        assertThat(payout.refundEach).isEqualTo(100L)
        assertThat(payout.winnerPayout).isEqualTo(0L)
    }

    @Test
    fun `loser's loss-streak increments`() {
        listener.handle(
            BettingMatchResolved(
                bettingMatch.id.value, matchId, challenger, opponent, challenger, "match_outcome",
            ),
        )
        val loserStreak = history.lossStreakOf(opponent)
        assertThat(loserStreak.consecutiveLosses).isEqualTo(1)
    }

    @Test
    fun `winner's loss-streak resets`() {
        // Seed a prior loss-streak for the winner so we can confirm it resets.
        history.saveLossStreak(
            history.lossStreakOf(challenger).applyLoss(now.minusSeconds(120), 5, 1_000L),
        )
        listener.handle(
            BettingMatchResolved(
                bettingMatch.id.value, matchId, challenger, opponent, challenger, "match_outcome",
            ),
        )
        assertThat(history.lossStreakOf(challenger).consecutiveLosses).isEqualTo(0)
    }

    @Test
    fun `CurrencyChanged published for winner with Betting reason`() {
        listener.handle(
            BettingMatchResolved(
                bettingMatch.id.value, matchId, challenger, opponent, challenger, "match_outcome",
            ),
        )
        val cc = eventBus.published.filterIsInstance<CurrencyChanged>()
            .filter { (it.reason as? CurrencyChangeReason.Betting)?.kind == "winner_payout" }
        assertThat(cc).hasSize(1)
        assertThat(cc.single().playerId).isEqualTo(challenger)
        assertThat(cc.single().delta).isEqualTo(200L)
    }
}
