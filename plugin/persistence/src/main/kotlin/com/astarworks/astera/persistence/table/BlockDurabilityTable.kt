package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * `block_durability` table — durable per-position custom block state
 * (Wave 5x, ADR-0090, V20 migration).
 *
 * Composite PK on `(world_id, pos_x, pos_y, pos_z)` matches the
 * `(WorldId, Vec3)` addressing in
 * [com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository].
 *
 * Vec3's `Double` axes are truncated to `INTEGER` here because block
 * placement is integral; the in-memory tracker performs the same
 * truncation when seeding from a place call. `current_hp` and `max_hp`
 * carry the Wave 5x per-block-type maximum so 5000-HP custom blocks
 * survive a restore without dropping back to a 100-HP default.
 *
 * `damaged_by` is nullable because environmental damage (lava, fall,
 * crumbling overhead block) carries no player attribution.
 */
public object BlockDurabilityTable : Table("block_durability") {
    public val worldId = varchar("world_id", 64)
    public val posX = integer("pos_x")
    public val posY = integer("pos_y")
    public val posZ = integer("pos_z")
    public val blockType = varchar("block_type", 128)
    public val currentHp = integer("current_hp").check { it greaterEq 0 }
    public val maxHp = integer("max_hp").check { it greaterEq 1 }
    public val lastDamagedTick = long("last_damaged_tick").check { it greaterEq 0L }
    public val damagedBy = javaUUID("damaged_by").nullable()
    override val primaryKey: PrimaryKey = PrimaryKey(worldId, posX, posY, posZ)
}
