package com.astarworks.astera.persistence.integration

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.usecase.social.JoinPartyUseCase
import com.astarworks.astera.core.application.usecase.social.PartyError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import com.astarworks.astera.persistence.repository.PostgresCurrencyLedger
import com.astarworks.astera.persistence.repository.PostgresPlayerAttributeContainer
import com.astarworks.astera.persistence.repository.PostgresPlayerInventorySnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wave 5server-harden / ADR-0123 — concurrent-write IT against real
 * Postgres + the in-memory party repository. Closes three audit P0 gaps:
 *
 *  1. **Wallet double-spend** — two threads racing on the same player's
 *     ledger debit. The Wave-F lock striping in [PostgresCurrencyLedger]
 *     forces serialisation, so exactly one debit succeeds and the other
 *     observes [WalletError.InsufficientFunds].
 *  2. **InventorySnapshot race** — two threads racing on `save()` for the
 *     same player. The Postgres upsert guarantees last-write-wins, so both
 *     calls succeed and `findLatest` returns one of the two written
 *     snapshots; this test pins the contract.
 *  3. **Party concurrent-join** — five threads racing on join for a
 *     `maxSize=4` party with one existing leader. Wave-F lock striping in
 *     [JoinPartyUseCase] keeps the use case atomic, so exactly three joins
 *     succeed and the fifth (4th candidate) sees [PartyError.PartyFull].
 *
 * Testcontainers-gated by [PostgresIntegrationTestBase]; skipped on CI
 * runners without Docker.
 */
public class ConcurrentWriteIT : PostgresIntegrationTestBase() {

    /** Wallet double-spend regression: exactly one of two concurrent debits succeeds. */
    @Test
    public fun `concurrent debit on the same wallet yields one success and one insufficient-funds`() {
        val containerStore = PostgresPlayerAttributeContainer(db)
        val ledger = PostgresCurrencyLedger(containerStore)
        val player = PlayerId(UUID.randomUUID())

        // Seed: exactly 100 GOLD — enough for one debit of 100 but not two.
        ledger.credit(player, Currency.GOLD, 100L)
        assertThat(ledger.balance(player, Currency.GOLD)).isEqualTo(100L)

        val attempts = 2
        val barrier = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(attempts)
        val successes = AtomicInteger(0)
        val failures = AtomicInteger(0)
        try {
            val futures = (1..attempts).map {
                pool.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    when (ledger.debit(player, Currency.GOLD, 100L)) {
                        is Result.Success -> successes.incrementAndGet()
                        is Result.Failure -> failures.incrementAndGet()
                    }
                }
            }
            barrier.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        assertThat(successes.get()).`as`("exactly one debit must succeed").isEqualTo(1)
        assertThat(failures.get()).`as`("exactly one debit must fail").isEqualTo(1)
        // Final balance: 100 - 100 = 0; double-spend would leave 100 - 0 (race) or 100 - 200 (uncapped) etc.
        assertThat(ledger.balance(player, Currency.GOLD)).isZero()
    }

    /** Inventory snapshot race: both saves commit, one of the two written snapshots survives. */
    @Test
    public fun `concurrent inventory snapshot saves on the same player end with last-write-wins`() {
        val repo = PostgresPlayerInventorySnapshotRepository(db)
        val player = PlayerId(UUID.randomUUID())
        val baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS)

        val snapA = PlayerInventorySnapshot(
            playerId = player,
            hotbar = List(9) {
                if (it == 0) InventoryItemSlot.ContentItem("astera:sword-a", 1) else null
            },
            main = List(27) { null },
            offhand = null,
            armor = List(4) { null },
            version = PlayerInventorySnapshot.CURRENT_VERSION,
            savedAt = baseTime,
        )
        val snapB = snapA.copy(
            hotbar = List(9) {
                if (it == 0) InventoryItemSlot.ContentItem("astera:sword-b", 1) else null
            },
            savedAt = baseTime.plusMillis(1),
        )

        val barrier = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(snapA, snapB).map { snap ->
                pool.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    repo.save(snap)
                }
            }
            barrier.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        val survivor = repo.findLatest(player)
        assertThat(survivor).isNotNull
        val survivorId = (survivor!!.hotbar[0] as InventoryItemSlot.ContentItem).itemId
        assertThat(survivorId)
            .`as`("survivor must be one of the two contenders (last-write-wins)")
            .isIn("astera:sword-a", "astera:sword-b")
    }

    /** Party concurrent-join: 5 threads, max-size 4 (with leader pre-seated): 3 succeed, 2 rejected. */
    @Test
    public fun `concurrent party join with maxSize 4 admits exactly 3 candidates`() {
        // Party repo is in-memory only by design (parties are runtime-scoped) —
        // the concurrency surface is the use case, which now uses Wave-F lock
        // striping.
        val repo = InMemoryPartyRepository()
        val joinUC = JoinPartyUseCase(repo)

        val leader = PlayerId(UUID.randomUUID())
        val partyId = PartyId("test-party")
        repo.upsert(
            Party(
                id = partyId,
                leader = leader,
                members = setOf(leader),
                maxSize = 4,
            ),
        )

        val candidates = List(5) { PlayerId(UUID.randomUUID()) }
        val barrier = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(candidates.size)
        val successes = AtomicInteger(0)
        val full = AtomicInteger(0)
        try {
            val futures = candidates.map { c ->
                pool.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    when (val r = joinUC.join(c, partyId)) {
                        is Result.Success -> successes.incrementAndGet()
                        is Result.Failure -> if (r.error is PartyError.PartyFull) full.incrementAndGet()
                    }
                }
            }
            barrier.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        assertThat(successes.get()).`as`("3 seats open + 5 candidates → 3 admitted").isEqualTo(3)
        assertThat(full.get()).`as`("2 candidates must be rejected with PartyFull").isEqualTo(2)
        val finalParty = repo.find(partyId)
        assertThat(finalParty).isNotNull
        assertThat(finalParty!!.members).hasSize(finalParty.maxSize)
    }
}
