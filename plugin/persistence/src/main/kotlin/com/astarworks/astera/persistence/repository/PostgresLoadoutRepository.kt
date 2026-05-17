package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.LoadoutsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/**
 * Postgres-backed [ILoadoutRepository]. Registered by
 * [com.astarworks.astera.persistence.platform.AsteraPersistencePlugin] at
 * priority=100, overlays the in-memory default registered by the weapons
 * plugin (Wave 4.5 / ADR-0027).
 *
 * Storage model: one row per (player, slot). [save] runs DELETE+INSERT inside
 * a single transaction so the multi-slot loadout either lands whole or not at
 * all. A more incremental upsert-per-slot strategy is possible but the row
 * count is bounded (six [LoadoutSlot] variants today) so the simpler atomic
 * replace is preferred — it also means the table state always matches the
 * domain aggregate exactly, with no stale rows from a previous larger loadout.
 *
 * Unknown values (slot names or item ids not parseable through
 * [LoadoutSlot.valueOf] / [ItemId]'s validator) are skipped during [find]
 * with no logging here — the persistence layer treats them as foreign data
 * and lets the domain decide. If you need diagnostic logging add it at the
 * call site, not in the repository.
 */
public class PostgresLoadoutRepository(private val db: Database) : ILoadoutRepository {

    override fun find(playerId: PlayerId): Loadout? = transaction(db) {
        val rows = LoadoutsTable
            .selectAll()
            .where { LoadoutsTable.playerId eq playerId.uuid }
            .toList()
        if (rows.isEmpty()) {
            null
        } else {
            val slots = rows.mapNotNull { row ->
                val slotName = row[LoadoutsTable.slot]
                val itemValue = row[LoadoutsTable.itemId]
                val slot = runCatching { LoadoutSlot.valueOf(slotName) }.getOrNull()
                val item = runCatching { ItemId(itemValue) }.getOrNull()
                if (slot != null && item != null) slot to item else null
            }.toMap()
            if (slots.isEmpty()) null else Loadout(slots)
        }
    }

    override fun save(playerId: PlayerId, loadout: Loadout): Unit = transaction(db) {
        // Atomic replace: drop the player's existing slot rows then write the
        // current aggregate state. Both run inside the same Exposed
        // transaction so any failure leaves the previous loadout intact.
        LoadoutsTable.deleteWhere { LoadoutsTable.playerId eq playerId.uuid }
        if (loadout.slots.isNotEmpty()) {
            val now = Instant.now()
            LoadoutsTable.batchInsert(loadout.slots.entries) { (slotKey, itemValue) ->
                this[LoadoutsTable.playerId] = playerId.uuid
                this[LoadoutsTable.slot] = slotKey.name
                this[LoadoutsTable.itemId] = itemValue.value
                this[LoadoutsTable.updatedAt] = now
            }
        }
        Unit
    }

    override fun all(): Collection<Pair<PlayerId, Loadout>> = transaction(db) {
        // Group rows by player_id then collapse each group into a Loadout.
        // Same skip-unknown semantics as [find].
        LoadoutsTable
            .selectAll()
            .toList()
            .groupBy { it[LoadoutsTable.playerId] }
            .mapNotNull { (uuid, playerRows) ->
                val slots = playerRows.mapNotNull { row ->
                    val slotName = row[LoadoutsTable.slot]
                    val itemValue = row[LoadoutsTable.itemId]
                    val slot = runCatching { LoadoutSlot.valueOf(slotName) }.getOrNull()
                    val item = runCatching { ItemId(itemValue) }.getOrNull()
                    if (slot != null && item != null) slot to item else null
                }.toMap()
                if (slots.isEmpty()) null else PlayerId(uuid) to Loadout(slots)
            }
    }
}
