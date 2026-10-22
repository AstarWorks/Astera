package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PostgresBettingEscrowConcurrencyIT : PostgresIntegrationTestBase() {
    private fun match(): BettingMatch {
        val match = BettingMatch.initiate(
            BettingMatchId.mint(), BettingPolicy.DEFAULT,
            PlayerId(UUID.randomUUID()), PlayerId(UUID.randomUUID()),
            BetStake(Currency.STAR, 100L), Instant.now(),
        )
        PostgresBettingMatchRepository(db).save(match)
        val ledger = PostgresCurrencyLedger(db)
        ledger.credit(match.challengerId, Currency.STAR, 1_000L)
        ledger.credit(match.opponentId, Currency.STAR, 1_000L)
        return match
    }

    @Test
    fun `missing escrow still returns a typed failure when no match row exists`() {
        val id = BettingMatchId.mint()
        val escrow = PostgresBettingEscrowService(db, PostgresCurrencyLedger(db))
        val missing = com.astarworks.astera.economy.domain.betting.BettingError.EscrowMissing(id)
        assertThat(escrow.releaseToWinner(id, PlayerId(UUID.randomUUID()), 1.0, Instant.now()).errorOrNull())
            .isEqualTo(missing)
        assertThat(escrow.refundBoth(id, Instant.now()).errorOrNull()).isEqualTo(missing)
    }

    @Test
    fun `concurrent holds debit each participant only once`() {
        val match = match()
        val outcomes = whileWalletLocked(match.challengerId) {
            PostgresBettingEscrowService(db, PostgresCurrencyLedger(db)).hold(
                match.id, match.challengerId, match.opponentId, Currency.STAR, 100L, Instant.now(),
            )
        }
        assertThat(outcomes.count { it is Result.Success }).isEqualTo(1)
        val ledger = PostgresCurrencyLedger(db)
        assertThat(ledger.balance(match.challengerId, Currency.STAR)).isEqualTo(900L)
        assertThat(ledger.balance(match.opponentId, Currency.STAR)).isEqualTo(900L)
    }

    @Test
    fun `concurrent payouts credit the winner only once`() {
        val match = match()
        val ledger = PostgresCurrencyLedger(db)
        val escrow = PostgresBettingEscrowService(db, ledger)
        escrow.hold(match.id, match.challengerId, match.opponentId, Currency.STAR, 100L, Instant.now())
        whileWalletLocked(match.challengerId) {
            PostgresBettingEscrowService(db, PostgresCurrencyLedger(db))
                .releaseToWinner(match.id, match.challengerId, 1.0, Instant.now())
        }
        assertThat(ledger.balance(match.challengerId, Currency.STAR)).isEqualTo(1_100L)
        assertThat(ledger.balance(match.opponentId, Currency.STAR)).isEqualTo(900L)
    }

    @Test
    fun `concurrent refunds return each stake only once`() {
        val match = match()
        val ledger = PostgresCurrencyLedger(db)
        PostgresBettingEscrowService(db, ledger)
            .hold(match.id, match.challengerId, match.opponentId, Currency.STAR, 100L, Instant.now())
        whileWalletLocked(match.challengerId) {
            PostgresBettingEscrowService(db, PostgresCurrencyLedger(db)).refundBoth(match.id, Instant.now())
        }
        assertThat(ledger.balance(match.challengerId, Currency.STAR)).isEqualTo(1_000L)
        assertThat(ledger.balance(match.opponentId, Currency.STAR)).isEqualTo(1_000L)
    }

    // Hold the first wallet write until all contenders are waiting in Postgres. Before the fix,
    // all have read the same escrow state; after it, one waits on the wallet and the rest on the match.
    private fun <T> whileWalletLocked(playerId: PlayerId, action: () -> T): List<T> {
        val workers = 4
        val pool = Executors.newFixedThreadPool(workers)
        try {
            context.dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.prepareStatement("SELECT 1 FROM player_attributes WHERE player_id = ? FOR UPDATE").use {
                    it.setObject(1, playerId.uuid)
                    it.executeQuery().use { rows -> check(rows.next()) }
                }
                val futures = (1..workers).map { pool.submit<T> { action() } }
                try {
                    context.dataSource.connection.use { observer ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        var waiting = 0
                        while (waiting < workers && System.nanoTime() < deadline) {
                            observer.createStatement().use { statement ->
                                statement.executeQuery(
                                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                                        "AND state = 'active' AND wait_event_type = 'Lock'",
                                ).use { rows -> rows.next(); waiting = rows.getInt(1) }
                            }
                            if (waiting < workers) Thread.sleep(10)
                        }
                        assertThat(waiting).`as`("all contenders reached the database lock").isEqualTo(workers)
                    }
                } finally {
                    blocker.rollback()
                }
                return futures.map { it.get(10, TimeUnit.SECONDS) }
            }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
