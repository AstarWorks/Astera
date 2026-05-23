package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingEscrowService
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingMatchRepository
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingOptInProvider
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingStatus
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class AcceptBettingChallengeUseCaseTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = BettingPolicy.testing()
    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val stake = BetStake(Currency.GOLD, 50L)
    private val matchId = MatchId("bet-accept-test")

    private val matches = InMemoryBettingMatchRepository()
    private val ledger = InMemoryCurrencyLedger()
    private val escrow = InMemoryBettingEscrowService(ledger)
    private val optIn = InMemoryBettingOptInProvider()
    private val eventBus = FakeDomainEventBus()

    private val uc = AcceptBettingChallengeUseCase(matches, escrow, optIn, ledger, eventBus, clock)

    @BeforeEach
    fun setUp() {
        optIn.setOptedIn(challenger, true)
        optIn.setOptedIn(opponent, true)
        // Seed funds.
        ledger.credit(challenger, Currency.GOLD, 1_000L)
        ledger.credit(opponent, Currency.GOLD, 1_000L)
    }

    private fun pending(): BettingMatch = matches.save(
        BettingMatch.initiate(BettingMatchId.mint(), policy, challenger, opponent, stake, now),
    )

    @Test
    fun `happy path debits both sides escrows + publishes two CurrencyChanged`() {
        val pending = pending()
        val res = uc.execute(pending.id, opponent, matchId)
        assertThat(res.isSuccess).isTrue()
        val active = res.getOrNull()!!
        assertThat(active.status).isEqualTo(BettingStatus.Active)
        assertThat(active.matchId).isEqualTo(matchId)
        assertThat(active.acceptedAt).isEqualTo(now)

        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(950L)
        assertThat(ledger.balance(opponent, Currency.GOLD)).isEqualTo(950L)

        val held = escrow.find(pending.id)
        assertThat(held).isNotNull
        assertThat(held!!.perSideAmount).isEqualTo(50L)

        val cc = eventBus.published.filterIsInstance<CurrencyChanged>()
        assertThat(cc).hasSize(2)
        assertThat(cc.map { it.delta }).containsExactlyInAnyOrder(-50L, -50L)
        assertThat(cc.map { (it.reason as CurrencyChangeReason.Betting).kind }.distinct())
            .containsExactly("escrow_debit")
    }

    @Test
    fun `unknown match`() {
        val res = uc.execute(BettingMatchId.mint(), opponent, matchId)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.UnknownMatch::class.java)
    }

    @Test
    fun `accepter not invited`() {
        val pending = pending()
        val stranger = PlayerId(UUID.randomUUID())
        val res = uc.execute(pending.id, stranger, matchId)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.NotInvited::class.java)
    }

    @Test
    fun `acceptance after deadline rejects`() {
        val pending = pending()
        val lateClock = Clock.fixed(pending.acceptanceDeadline.plusSeconds(1), ZoneOffset.UTC)
        val lateUc = AcceptBettingChallengeUseCase(matches, escrow, optIn, ledger, eventBus, lateClock)
        val res = lateUc.execute(pending.id, opponent, matchId)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.AcceptanceDeadlineExpired::class.java)
    }

    @Test
    fun `insufficient funds on opponent leaves challenger with their balance`() {
        val pending = pending()
        // drain opponent's funds.
        ledger.debit(opponent, Currency.GOLD, 1_000L)
        val res = uc.execute(pending.id, opponent, matchId)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.EscrowDebitFailed::class.java)
        // Challenger must not have been debited.
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(1_000L)
        assertThat(ledger.balance(opponent, Currency.GOLD)).isEqualTo(0L)
        // The match is unchanged (still pending).
        assertThat(matches.findById(pending.id)!!.status).isEqualTo(BettingStatus.PendingAcceptance)
    }

    @Test
    fun `cannot accept twice`() {
        val pending = pending()
        uc.execute(pending.id, opponent, matchId)
        val second = uc.execute(pending.id, opponent, matchId)
        assertThat(second.errorOrNull()).isInstanceOf(BettingError.NotPending::class.java)
    }
}
