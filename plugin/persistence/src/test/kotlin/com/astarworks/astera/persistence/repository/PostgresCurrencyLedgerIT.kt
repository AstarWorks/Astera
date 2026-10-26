package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0201: the wallet is consistent across servers. Two "servers" are two
 * independent ledger + container-store instances (separate caches, as in two
 * JVMs) over the same database.
 */
public class PostgresCurrencyLedgerIT : PostgresIntegrationTestBase() {

    private fun id() = PlayerId(UUID.randomUUID())

    @Test
    public fun `a credit on one server is visible on another at once`() {
        kotlinx.coroutines.runBlocking {
        val game = PostgresCurrencyLedger(db)
        val lobby = PostgresCurrencyLedger(db)
        val p = id()

        game.credit(p, Currency.GOLD, 120)
        assertThat(lobby.balance(p, Currency.GOLD)).isEqualTo(120)
        lobby.credit(p, Currency.GOLD, 5)
        assertThat(game.balance(p, Currency.GOLD)).isEqualTo(125)
        assertThat(game.wallet(p).balances()).containsEntry(Currency.GOLD, 125L)
        }
    }

    @Test
    public fun `debit reports the real balance and never goes negative`() {
        kotlinx.coroutines.runBlocking {
        val ledger = PostgresCurrencyLedger(db)
        val p = id()
        ledger.credit(p, Currency.GOLD, 50)

        val failure = ledger.debit(p, Currency.GOLD, 80) as Result.Failure
        assertThat(failure.error).isEqualTo(WalletError.InsufficientFunds(Currency.GOLD, have = 50, tried = 80))
        assertThat(ledger.balance(p, Currency.GOLD)).isEqualTo(50)

        assertThat(ledger.debit(p, Currency.GOLD, 50)).isInstanceOf(Result.Success::class.java)
        assertThat(ledger.balance(p, Currency.GOLD)).isZero()
        assertThat(ledger.debit(p, Currency.GEM, 1)).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    public fun `a stale container write on another server does not roll the wallet back`() {
        kotlinx.coroutines.runBlocking {
        val lobbyStore = attributeStore()
        val lobbyLedger = PostgresCurrencyLedger(db)
        val gameLedger = PostgresCurrencyLedger(db)
        val p = id()

        // The lobby loads the player (wallet 10) and keeps the container cached.
        gameLedger.credit(p, Currency.GOLD, 10)
        val cached = lobbyStore.load(p).containerFor(p)
        assertThat((cached.get(Attributes.WALLET_GOLD) as AttributeValue.NumberValue).value).isEqualTo(10.0)

        // The game server pays out a match reward, then the lobby saves an unrelated stat.
        gameLedger.credit(p, Currency.GOLD, 100)
        cached.set(Attributes.STAT_KILLS, AttributeValue.NumberValue(3.0))

        writes.read { lobbyLedger.preload(p) }.join()
        assertThat(lobbyLedger.balance(p, Currency.GOLD)).`as`("money survives a stale whole-document write").isEqualTo(110)
        val fresh = attributeStore().load(p).containerFor(p)
        assertThat((fresh.get(Attributes.STAT_KILLS) as AttributeValue.NumberValue).value).isEqualTo(3.0)
        }
    }

    @Test
    public fun `concurrent debits from two servers spend a balance exactly once`() {
        kotlinx.coroutines.runBlocking {
        val a = PostgresCurrencyLedger(db)
        val b = PostgresCurrencyLedger(db)
        val p = id()
        a.credit(p, Currency.GOLD, 100)

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val ok = AtomicInteger()
        try {
            val futures = (0 until 8).map { i ->
                pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    val ledger = if (i % 2 == 0) a else b
                    if (kotlinx.coroutines.runBlocking { ledger.debit(p, Currency.GOLD, 30) } is Result.Success) ok.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
        assertThat(ok.get()).isEqualTo(3)
        assertThat(a.balance(p, Currency.GOLD)).isEqualTo(10)
        }
}

    @Test
    public fun `transfer moves money atomically and fails cleanly when short`() {
        kotlinx.coroutines.runBlocking {
        val ledger = PostgresCurrencyLedger(db)
        val from = id()
        val to = id()
        ledger.credit(from, Currency.GOLD, 40)

        assertThat(ledger.transfer(from, to, Currency.GOLD, 100)).isInstanceOf(Result.Failure::class.java)
        assertThat(ledger.balance(from, Currency.GOLD)).isEqualTo(40)
        assertThat(ledger.balance(to, Currency.GOLD)).isZero()

        assertThat(ledger.transfer(from, to, Currency.GOLD, 15)).isInstanceOf(Result.Success::class.java)
        assertThat(ledger.balance(from, Currency.GOLD)).isEqualTo(25)
        assertThat(ledger.balance(to, Currency.GOLD)).isEqualTo(15)
        }
}
}
