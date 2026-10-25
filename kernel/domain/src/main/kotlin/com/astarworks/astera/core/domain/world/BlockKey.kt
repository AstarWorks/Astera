package com.astarworks.astera.core.domain.world

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.floor

/**
 * Composite key for tracked block positions (Wave 6c, ADR-0160).
 *
 * Lifted from `PaperBlockStateGateway`'s private nested class so the
 * `IBlockChangeJournalRepository` outbound port can speak in domain
 * vocabulary without leaking Paper/Bukkit types or duplicating Vec3
 * truncation logic.
 *
 * # Why integer coordinates
 *
 * `Vec3` carries doubles for skill projectile / particle math, but Bukkit
 * blocks are integer cells. Two callers that hand the gateway the same
 * cell with different fractional offsets (e.g. an entity at
 * `(3.7, 64.0, 5.2)` and a click at `(3.1, 64.4, 5.9)`) must collide on
 * the same key. [of] normalizes via `floor` to match Bukkit's
 * `World.getBlockAt(x, y, z)` semantics.
 *
 * # Why worldId as String, not WorldId
 *
 * Pragma: the underlying value already matches Postgres `VARCHAR(64)` /
 * `UUID` storage shapes downstream, and the data class auto-`hashCode`
 * stays cheap with a plain String. Construct from a `WorldId` via [of].
 */
public data class BlockKey(
    val worldId: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    public companion object {
        /**
         * Build a [BlockKey] from `(WorldId, Vec3)`. Vec3's fractional axes
         * are floored — two Vec3s that resolve to the same Bukkit cell
         * collide on the same key (per [BlockKey] KDoc).
         */
        public fun of(world: WorldId, pos: Vec3): BlockKey = BlockKey(
            worldId = world.value,
            x = floor(pos.x).toInt(),
            y = floor(pos.y).toInt(),
            z = floor(pos.z).toInt(),
        )
    }
}
