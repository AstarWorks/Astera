package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import com.astarworks.astera.stats.domain.level.LevelReward
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

public class PostgresLevelRewardsAndSeasonIT : PostgresIntegrationTestBase() {
    @Test
    public fun `competing backends credit one reward and restart preserves claims and unlocks`() {
        val player = PlayerId(UUID.randomUUID())
        val reward = LevelReward(gold = 52, titles = listOf("newcomer"), items = listOf("training-sword"))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..12).map { Callable {
                PostgresLevelRewardRepository(db).apply(player, 2, reward).getOrNull()
            } }).map { it.get() }
            assertThat(results.count { it == true }).isEqualTo(1)
            assertThat(results.count { it == false }).isEqualTo(11)
        } finally { pool.shutdownNow() }
        val restarted = PostgresLevelRewardRepository(db)
        assertThat(restarted.apply(player, 2, reward).getOrNull()).isFalse()
        assertThat(restarted.unlocks(player)).containsExactlyInAnyOrder("title:newcomer", "item:training-sword")
        assertThat(PostgresCurrencyLedger(db).balance(player, Currency.GOLD)).isEqualTo(52)
    }

    @Test
    public fun `rollback undoes the claim wallet credits and unlocks together`() {
        val player = PlayerId(UUID.randomUUID())
        val reward = LevelReward(gold = 77, cosmetics = listOf("spark"))
        assertThatThrownBy {
            transaction(db) {
                assertThat(PostgresLevelRewardRepository(db).apply(player, 3, reward).getOrNull()).isTrue()
                error("Simulated transaction failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        val restarted = PostgresLevelRewardRepository(db)
        assertThat(restarted.unlocks(player)).isEmpty()
        assertThat(PostgresCurrencyLedger(db).balance(player, Currency.GOLD)).isZero()
        assertThat(restarted.apply(player, 3, reward).getOrNull()).isTrue()
        assertThat(PostgresCurrencyLedger(db).balance(player, Currency.GOLD)).isEqualTo(77)
    }

    @Test
    public fun `concurrent season check and restart share one durable reset`() {
        val key = "test-${UUID.randomUUID()}"
        val before = PostgresSeasonRepository(db).all().size
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map { Callable {
                PostgresSeasonRepository(db).startOnce(key, "Summer", Instant.now())
            } }).map { it.get() }
            assertThat(results.filterNotNull()).hasSize(1)
        } finally { pool.shutdownNow() }
        assertThat(PostgresSeasonRepository(db).startOnce(key, "Summer", Instant.now())).isNull()
        assertThat(PostgresSeasonRepository(db).all()).hasSize(before + 1)
        assertThat(PostgresSeasonRepository(db).all().count { it.isActive }).isEqualTo(1)
    }
}
