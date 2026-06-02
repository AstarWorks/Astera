package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.jsonb
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.Table

/**
 * `block_change_journal` table — write-through per-cell block state journal
 * (Wave 6c, ADR-0160, V45 migration).
 *
 * Composite PK on `(world_id, pos_x, pos_y, pos_z)` matches
 * [com.astarworks.astera.core.domain.world.BlockKey]'s identity. The
 * `state` column is a JSONB blob carrying the full `BlockState` payload as
 * a JSON-encoded String — Konsist rule #6 forbids `@Serializable` on
 * `core.domain.world.BlockState`, so the repository encodes BlockState to
 * a JSON string via a kotlinx-serialization adapter at the persistence
 * boundary and stores the resulting String here.
 */
public object BlockChangeJournalTable : Table("block_change_journal") {
    public val worldId = varchar("world_id", 64)
    public val posX = integer("pos_x")
    public val posY = integer("pos_y")
    public val posZ = integer("pos_z")
    public val state = jsonb("state", String.serializer())
    override val primaryKey: PrimaryKey = PrimaryKey(worldId, posX, posY, posZ)
}
