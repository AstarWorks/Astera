package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `loadouts` table — per-(player, slot) loadout assignments.
 *
 * Wave 4.5 (R3.5) / ADR-0027. Compound PK on `(player_id, slot)` enforces the
 * "one item per slot" invariant from the [com.astarworks.astera.core.domain.player.Loadout]
 * aggregate at the DB layer so direct SQL writes also respect it.
 *
 * `slot` is stored as the [com.astarworks.astera.core.domain.player.LoadoutSlot]
 * enum name (varchar 32) rather than an enum type so a future
 * [com.astarworks.astera.core.domain.player.LoadoutSlot] addition does not
 * require an `ALTER TYPE`. `itemId` is stored as the
 * [com.astarworks.astera.core.domain.item.ItemId.value] (varchar 128 — fits
 * lower-kebab-case identifiers with comfortable headroom).
 */
public object LoadoutsTable : Table("loadouts") {
    public val playerId = javaUUID("player_id")
    public val slot = varchar("slot", 32)
    public val itemId = varchar("item_id", 128)
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, slot)
}
