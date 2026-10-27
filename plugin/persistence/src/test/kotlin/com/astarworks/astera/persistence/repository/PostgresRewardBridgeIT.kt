package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.reward.RewardBundle
import com.astarworks.astera.core.domain.reward.RewardBundleId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Round-trip IT for [PostgresRewardBridge] (Wave _persist-1 / ADR-0068)
 * against real Postgres via Testcontainers. Skipped (via
 * [org.junit.jupiter.api.Assumptions]) when Docker is unavailable.
 *
 * Covers the outbox semantics: enqueue + consume round-trip, delivered
 * marking is idempotent (second consume returns empty), peek does not
 * consume, multiple bundles drain in creation order, per-player isolation
 * holds.
 */
public class PostgresRewardBridgeIT : PostgresIntegrationTestBase() {

    @Test
    public fun `enqueue + consumeForPlayer round-trips one bundle with items, currency, and xp`() {
        val repo = PostgresRewardBridge(db)
        val playerId = PlayerId(UUID.randomUUID())
        val bundle = RewardBundle(
            id = RewardBundleId.random(),
            playerId = playerId,
            items = listOf(
                RewardBundle.RewardItem("astera:sword", count = 1),
                RewardBundle.RewardItem("astera:potion", count = 8),
            ),
            currency = mapOf(Currency.GOLD to 100L),
            xp = 250L,
            // Truncate to microseconds: Postgres TIMESTAMP stores 6 fractional digits,
            // Java Instant has 9 (nanoseconds). The round-trip truncates the last 3
            // digits otherwise.
            createdAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS),
        )

        repo.enqueue(bundle)
        val consumed = repo.consumeForPlayer(playerId)

        assertThat(consumed).hasSize(1)
        val got = consumed.single()
        assertThat(got.id).isEqualTo(bundle.id)
        assertThat(got.playerId).isEqualTo(bundle.playerId)
        assertThat(got.items.map { it.itemId }).containsExactly("astera:sword", "astera:potion")
        assertThat(got.items.map { it.count }).containsExactly(1, 8)
        assertThat(got.currency).isEqualTo(mapOf(Currency.GOLD to 100L))
        assertThat(got.xp).isEqualTo(250L)
        assertThat(got.createdAt).isEqualTo(bundle.createdAt)
    }

    @Test
    public fun `consumeForPlayer marks rows delivered so the second call returns empty`() {
        val repo = PostgresRewardBridge(db)
        val playerId = PlayerId(UUID.randomUUID())
        repo.enqueue(simpleBundle(playerId))

        val first = repo.consumeForPlayer(playerId)
        val second = repo.consumeForPlayer(playerId)

        assertThat(first).hasSize(1)
        assertThat(second).isEmpty()
    }

    @Test
    public fun `peek returns undelivered bundles without marking them delivered`() {
        val repo = PostgresRewardBridge(db)
        val playerId = PlayerId(UUID.randomUUID())
        val bundle = simpleBundle(playerId)
        repo.enqueue(bundle)

        val peeked = repo.peek(playerId)
        // A second peek still sees the row — peek is a pure read.
        val peekedAgain = repo.peek(playerId)
        // And a real consume can still drain it.
        val consumed = repo.consumeForPlayer(playerId)

        assertThat(peeked.map { it.id }).containsExactly(bundle.id)
        assertThat(peekedAgain.map { it.id }).containsExactly(bundle.id)
        assertThat(consumed.map { it.id }).containsExactly(bundle.id)
    }

    @Test
    public fun `peek does not return bundles that have already been consumed`() {
        val repo = PostgresRewardBridge(db)
        val playerId = PlayerId(UUID.randomUUID())
        repo.enqueue(simpleBundle(playerId))
        repo.consumeForPlayer(playerId)

        assertThat(repo.peek(playerId)).isEmpty()
    }

    @Test
    public fun `consumeForPlayer returns multiple bundles in creation order`() {
        val repo = PostgresRewardBridge(db)
        val playerId = PlayerId(UUID.randomUUID())
        val base = Instant.now().minusSeconds(300)
        val first = simpleBundle(playerId, createdAt = base)
        val second = simpleBundle(playerId, createdAt = base.plusSeconds(60))
        val third = simpleBundle(playerId, createdAt = base.plusSeconds(120))

        // Enqueue out of creation order to make sure the ORDER BY drives
        // the result, not the insert order.
        repo.enqueue(third)
        repo.enqueue(first)
        repo.enqueue(second)

        val drained = repo.consumeForPlayer(playerId)

        assertThat(drained.map { it.id }).containsExactly(first.id, second.id, third.id)
    }

    @Test
    public fun `enqueue is isolated per player — other players bundles are not returned`() {
        val repo = PostgresRewardBridge(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val bundleA = simpleBundle(a)
        val bundleB1 = simpleBundle(b)
        val bundleB2 = simpleBundle(b)

        repo.enqueue(bundleA)
        repo.enqueue(bundleB1)
        repo.enqueue(bundleB2)

        val drainedA = repo.consumeForPlayer(a)
        val drainedB = repo.consumeForPlayer(b)

        assertThat(drainedA.map { it.id }).containsExactly(bundleA.id)
        assertThat(drainedB.map { it.id }).containsExactlyInAnyOrder(bundleB1.id, bundleB2.id)
    }

    @Test
    public fun `consumeForPlayer on an empty queue returns an empty list`() {
        val repo = PostgresRewardBridge(db)
        assertThat(repo.consumeForPlayer(PlayerId(UUID.randomUUID()))).isEmpty()
    }

    @Test
    public fun `peek on an empty queue returns an empty list`() {
        val repo = PostgresRewardBridge(db)
        assertThat(repo.peek(PlayerId(UUID.randomUUID()))).isEmpty()
    }

    // Atomicity (SELECT-then-UPDATE within a single transaction) is enforced
    // by Postgres at the JDBC layer — exercising the rollback path
    // deterministically would require an injected fault mid-transaction,
    // which Testcontainers does not surface cleanly. The semantics are
    // documented on PostgresRewardBridge.consumeForPlayer + V15 migration;
    // the contract test sits at the use-case layer (RewardApplicationUseCase
    // dedupes on RewardBundleId).

    private fun simpleBundle(
        playerId: PlayerId,
        createdAt: Instant = Instant.now(),
    ): RewardBundle = RewardBundle(
        id = RewardBundleId.random(),
        playerId = playerId,
        items = listOf(RewardBundle.RewardItem("astera:emerald", count = 32)),
        currency = mapOf(Currency.GOLD to 50L),
        xp = 10L,
        createdAt = createdAt,
    )
}
