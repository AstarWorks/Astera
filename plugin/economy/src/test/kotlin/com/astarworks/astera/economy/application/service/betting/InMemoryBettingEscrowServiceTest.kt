package com.astarworks.astera.economy.application.service.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingEscrowStatus
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class InMemoryBettingEscrowServiceTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val bmId = BettingMatchId.mint()

    private val ledger = InMemoryCurrencyLedger().apply {
        credit(challenger, Currency.GOLD, 1_000L)
        credit(opponent, Currency.GOLD, 1_000L)
    }
    private val escrow = InMemoryBettingEscrowService(ledger)

    @Test
    fun `hold debits both sides`() {
        val res = escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        assertThat(res.isSuccess).isTrue()
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(900L)
        assertThat(ledger.balance(opponent, Currency.GOLD)).isEqualTo(900L)
        val record = res.getOrNull()!!
        assertThat(record.status).isEqualTo(BettingEscrowStatus.Held)
    }

    @Test
    fun `hold is idempotent — second call returns AlreadyEscrowed`() {
        escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        val res = escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.AlreadyEscrowed::class.java)
    }

    @Test
    fun `hold rolls back challenger if opponent debit fails`() {
        ledger.debit(opponent, Currency.GOLD, 1_000L)
        val res = escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.EscrowDebitFailed::class.java)
        // Challenger restored.
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(1_000L)
    }

    @Test
    fun `releaseToWinner credits the winner full pot at split 1`() {
        escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        val res = escrow.releaseToWinner(bmId, challenger, 1.0, now.plusSeconds(10))
        assertThat(res.isSuccess).isTrue()
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(1_100L)
        assertThat(res.getOrNull()!!.status).isEqualTo(BettingEscrowStatus.Paid)
    }

    @Test
    fun `releaseToWinner with split 0_95 floors payout to long`() {
        escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        escrow.releaseToWinner(bmId, challenger, 0.95, now.plusSeconds(10))
        // 200 * 0.95 = 190
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(1_090L)
    }

    @Test
    fun `releaseToWinner is idempotent`() {
        escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        escrow.releaseToWinner(bmId, challenger, 1.0, now.plusSeconds(10))
        val res = escrow.releaseToWinner(bmId, challenger, 1.0, now.plusSeconds(20))
        assertThat(res.isSuccess).isTrue()
        // Still only one credit on the winner.
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(1_100L)
    }

    @Test
    fun `refundBoth credits each side`() {
        escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        val res = escrow.refundBoth(bmId, now.plusSeconds(10))
        assertThat(res.isSuccess).isTrue()
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(1_000L)
        assertThat(ledger.balance(opponent, Currency.GOLD)).isEqualTo(1_000L)
        assertThat(res.getOrNull()!!.status).isEqualTo(BettingEscrowStatus.Refunded)
    }

    @Test
    fun `refundBoth is idempotent`() {
        escrow.hold(bmId, challenger, opponent, Currency.GOLD, 100L, now)
        escrow.refundBoth(bmId, now.plusSeconds(10))
        val res = escrow.refundBoth(bmId, now.plusSeconds(20))
        assertThat(res.isSuccess).isTrue()
        assertThat(ledger.balance(challenger, Currency.GOLD)).isEqualTo(1_000L)
    }

    @Test
    fun `releaseToWinner before hold returns EscrowMissing`() {
        val res = escrow.releaseToWinner(bmId, challenger, 1.0, now)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.EscrowMissing::class.java)
    }
}
