package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticLoadoutRepository
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.cosmetic.CosmeticLoadout
import com.astarworks.astera.core.domain.player.PlayerId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

/** Equipped cosmetics, one row per (player, kind) in `player_cosmetic_loadout` (V70). */
public class PostgresCosmeticLoadoutRepository(private val db: Database) : ICosmeticLoadoutRepository {

    override fun find(playerId: PlayerId): CosmeticLoadout = transaction(db) {
        (connection.connection as Connection).prepareStatement(
            "SELECT kind, cosmetic_id FROM player_cosmetic_loadout WHERE player_id = ?",
        ).use { st ->
            st.setObject(1, playerId.uuid)
            st.executeQuery().use { rs ->
                var loadout = CosmeticLoadout.EMPTY
                while (rs.next()) {
                    val kind = runCatching { CosmeticKind.valueOf(rs.getString(1)) }.getOrNull() ?: continue
                    loadout = loadout.with(kind, rs.getString(2))
                }
                loadout
            }
        }
    }

    override fun set(playerId: PlayerId, kind: CosmeticKind, cosmeticId: String?) {
        transaction(db) {
            val c = connection.connection as Connection
            if (cosmeticId == null) {
                c.prepareStatement("DELETE FROM player_cosmetic_loadout WHERE player_id = ? AND kind = ?").use { st ->
                    st.setObject(1, playerId.uuid)
                    st.setString(2, kind.name)
                    st.executeUpdate()
                }
            } else {
                c.prepareStatement(
                    "INSERT INTO player_cosmetic_loadout (player_id, kind, cosmetic_id) VALUES (?, ?, ?) " +
                        "ON CONFLICT (player_id, kind) DO UPDATE SET cosmetic_id = EXCLUDED.cosmetic_id, updated_at = now()",
                ).use { st ->
                    st.setObject(1, playerId.uuid)
                    st.setString(2, kind.name)
                    st.setString(3, cosmeticId)
                    st.executeUpdate()
                }
            }
        }
    }
}
