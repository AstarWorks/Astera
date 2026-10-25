package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelRewardRepository
import com.astarworks.astera.stats.application.port.outbound.LevelRewardError
import com.astarworks.astera.stats.domain.level.LevelReward
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

/** Claims, entitlements and ledger credit share the same Exposed transaction/database. */
public class PostgresLevelRewardRepository(private val db: Database) : ILevelRewardRepository {
    private val ledger = PostgresCurrencyLedger(db)

    override fun apply(playerId: PlayerId, level: Int, reward: LevelReward): Result<Boolean, LevelRewardError> =
        runCatching {
            transaction(db) {
                val c = connection.connection as Connection
                val inserted = c.prepareStatement(
                    "INSERT INTO level_reward_claims (player_id, level) VALUES (?, ?) ON CONFLICT DO NOTHING",
                ).use { st ->
                    st.setObject(1, playerId.uuid)
                    st.setInt(2, level)
                    st.executeUpdate() > 0
                }
                if (!inserted) return@transaction false
                ledger.credit(playerId, Currency.GOLD, reward.gold)
                for (unlock in reward.unlocks.sorted()) {
                    c.prepareStatement(
                        "INSERT INTO player_unlocks (player_id, unlock_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    ).use { st ->
                        st.setObject(1, playerId.uuid)
                        st.setString(2, unlock)
                        st.executeUpdate()
                    }
                }
                true
            }
        }.fold({ Result.success(it) }, { Result.failure(LevelRewardError.Storage(it.message ?: "Reward transaction failed")) })

    override fun unlocks(playerId: PlayerId): Set<String> = transaction(db) {
        (connection.connection as Connection).prepareStatement(
            "SELECT unlock_id FROM player_unlocks WHERE player_id = ? ORDER BY unlock_id",
        ).use { st ->
            st.setObject(1, playerId.uuid)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    override fun grant(playerId: PlayerId, unlockId: String): Boolean = transaction(db) {
        (connection.connection as Connection).prepareStatement(
            "INSERT INTO player_unlocks (player_id, unlock_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        ).use { st ->
            st.setObject(1, playerId.uuid)
            st.setString(2, unlockId)
            st.executeUpdate() > 0
        }
    }
}
