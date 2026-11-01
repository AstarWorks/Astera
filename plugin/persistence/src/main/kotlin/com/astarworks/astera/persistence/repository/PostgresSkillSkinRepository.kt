package com.astarworks.astera.persistence.repository

import com.astarworks.astera.combat.application.port.outbound.skin.ISkillSkinRepository
import com.astarworks.astera.core.domain.player.PlayerId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/**
 * Owned and selected skill skins, one row per owned skin in `player_skill_skin` (V74). The cast path reads [selected] on every cast, so
 * each (player, skill) is loaded once and then served from memory; writes go to Postgres first and update the cached slot.
 */
public class PostgresSkillSkinRepository(private val db: Database) : ISkillSkinRepository {
    private class Slot(val owned: MutableSet<String>, @Volatile var selected: String?)

    private val cache = ConcurrentHashMap<Pair<PlayerId, String>, Slot>()

    private fun slot(player: PlayerId, base: String): Slot = cache.computeIfAbsent(player to base) {
        transaction(db) {
            (connection.connection as Connection).prepareStatement(
                "SELECT skin_id, selected FROM player_skill_skin WHERE player_id = ? AND skill_id = ?",
            ).use { st ->
                st.setObject(1, player.uuid)
                st.setString(2, base)
                st.executeQuery().use { rs ->
                    val owned = ConcurrentHashMap.newKeySet<String>()
                    var selected: String? = null
                    while (rs.next()) {
                        owned += rs.getString(1)
                        if (rs.getBoolean(2)) selected = rs.getString(1)
                    }
                    Slot(owned, selected)
                }
            }
        }
    }

    override fun selected(player: PlayerId, baseSkillId: String): String? = slot(player, baseSkillId).selected

    override fun owned(player: PlayerId, baseSkillId: String): Set<String> = slot(player, baseSkillId).owned.toSet()

    override fun grant(player: PlayerId, baseSkillId: String, skin: String) {
        val s = slot(player, baseSkillId)
        transaction(db) {
            (connection.connection as Connection).prepareStatement(
                "INSERT INTO player_skill_skin (player_id, skill_id, skin_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            ).use { st ->
                st.setObject(1, player.uuid)
                st.setString(2, baseSkillId)
                st.setString(3, skin)
                st.executeUpdate()
            }
        }
        s.owned += skin
    }

    override fun revoke(player: PlayerId, baseSkillId: String, skin: String) {
        val s = slot(player, baseSkillId)
        transaction(db) {
            (connection.connection as Connection).prepareStatement(
                "DELETE FROM player_skill_skin WHERE player_id = ? AND skill_id = ? AND skin_id = ?",
            ).use { st ->
                st.setObject(1, player.uuid)
                st.setString(2, baseSkillId)
                st.setString(3, skin)
                st.executeUpdate()
            }
        }
        s.owned -= skin
        if (s.selected == skin) s.selected = null
    }

    override fun select(player: PlayerId, baseSkillId: String, skin: String?): Boolean {
        val s = slot(player, baseSkillId)
        if (skin != null && skin !in s.owned) return false
        transaction(db) {
            val c = connection.connection as Connection
            // clear first: the partial unique index allows one selected row, and one UPDATE could set the new one before clearing the old
            c.prepareStatement("UPDATE player_skill_skin SET selected = FALSE, updated_at = now() WHERE player_id = ? AND skill_id = ? AND selected").use { st ->
                st.setObject(1, player.uuid)
                st.setString(2, baseSkillId)
                st.executeUpdate()
            }
            if (skin != null) {
                c.prepareStatement("UPDATE player_skill_skin SET selected = TRUE, updated_at = now() WHERE player_id = ? AND skill_id = ? AND skin_id = ?").use { st ->
                    st.setObject(1, player.uuid)
                    st.setString(2, baseSkillId)
                    st.setString(3, skin)
                    st.executeUpdate()
                }
            }
        }
        s.selected = skin
        return true
    }
}
