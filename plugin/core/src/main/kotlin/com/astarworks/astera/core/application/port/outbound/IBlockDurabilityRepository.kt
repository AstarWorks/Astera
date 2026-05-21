package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: persistent storage for per-position block durability
 * (Wave 5x, ADR-0090).
 *
 * Bridges the in-memory `IBlockStateGateway` (per-server-process tracker)
 * to durable storage so custom block damage and placement **survives server
 * restart**. Wave 5d's `BlockChangeJournal` was deliberately match-scoped +
 * in-memory (5000-entry ArrayDeque, ADR-0033); Wave 5x's custom blocks are
 * placed in the open world and must persist across reboots.
 *
 * # Lifecycle (Wave 5w lifecycle listener integration)
 *
 * - **Server start**: `AsteraCorePlugin.onEnable` calls
 *   [loadAll]; the gateway hydrates its in-memory tracker from the
 *   returned map before any feature plugin has a chance to query.
 * - **Periodic flush** (every N ticks, default 600 = 30s): the gateway
 *   collects dirty positions and calls [upsertAll] inside a single
 *   transaction (in Postgres) or a single atomic swap (in-memory).
 * - **Server stop**: `onDisable` calls [upsertAll] one final time with
 *   the full tracker snapshot so no in-flight damage is lost.
 *
 * # Composite key
 *
 * Positions are keyed by `(WorldId, Vec3)` since the same coordinates may
 * carry independent state in different worlds (per-match arenas + open
 * world coexist). The Postgres impl encodes Vec3 as three INT columns and
 * uses `(world_id, pos_x, pos_y, pos_z)` as the composite primary key
 * (V20 migration).
 *
 * # Wave _persist-1 alignment
 *
 * Same priority-100 overlay pattern as `IPlayerSessionRepository`
 * (ADR-0068): the in-memory default registers at priority 0 in
 * `AsteraCorePlugin`; the Postgres impl in `AsteraPersistencePlugin`
 * overlays at priority 100 when `ASTERA_PG_URL` is present.
 */
public interface IBlockDurabilityRepository {

    /**
     * Load every persisted block state for [world]. Called once at server
     * startup so the in-memory tracker matches durable storage before any
     * query lands.
     */
    public fun loadAll(world: WorldId): Result<Map<Vec3, BlockState>, BlockGatewayError>

    /**
     * Bulk upsert a snapshot of tracked positions for [world]. The Postgres
     * impl wraps this in a single transaction so the durable view is always
     * self-consistent. Positions present in the prior load but absent from
     * [states] are NOT deleted — call [deletePositions] explicitly for that
     * (custom blocks restored to vanilla on `MatchEnded` go through delete).
     */
    public fun upsertAll(
        world: WorldId,
        states: Map<Vec3, BlockState>,
    ): Result<Unit, BlockGatewayError>

    /** Delete a single tracked position (e.g. restored vanilla block). */
    public fun deletePosition(
        world: WorldId,
        pos: Vec3,
    ): Result<Unit, BlockGatewayError>

    /** Bulk delete (post-match restoration). */
    public fun deletePositions(
        world: WorldId,
        positions: Collection<Vec3>,
    ): Result<Unit, BlockGatewayError>
}
