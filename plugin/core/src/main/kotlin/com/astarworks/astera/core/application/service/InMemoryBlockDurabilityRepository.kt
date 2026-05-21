package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Phase-1 in-memory [IBlockDurabilityRepository] (Wave 5x, ADR-0090).
 *
 * Default registered by `AsteraCorePlugin` at priority=0; overlaid by the
 * Postgres impl in `AsteraPersistencePlugin` at priority=100 when
 * `ASTERA_PG_URL` is set.
 *
 * # Use as fallback
 *
 * When the operator runs Astera without Postgres (single-node PoC,
 * dev sandbox, smoke test), this impl satisfies the port surface but
 * provides zero durability — server restart wipes the tracker. The Postgres
 * overlay is the production path for cross-restart custom-block persistence.
 *
 * Thread-safety: same Astera invariant as `InMemoryBlockStateGateway` —
 * mutation runs on the server main thread (per `IScheduler` regional
 * contract). Plain `MutableMap` is intentional.
 */
public class InMemoryBlockDurabilityRepository : IBlockDurabilityRepository {

    private val store: MutableMap<WorldId, MutableMap<Vec3, BlockState>> = mutableMapOf()

    override fun loadAll(world: WorldId): Result<Map<Vec3, BlockState>, BlockGatewayError> =
        Result.success(store[world]?.toMap() ?: emptyMap())

    override fun upsertAll(
        world: WorldId,
        states: Map<Vec3, BlockState>,
    ): Result<Unit, BlockGatewayError> {
        val bucket = store.getOrPut(world) { mutableMapOf() }
        bucket.putAll(states)
        return Result.success(Unit)
    }

    override fun deletePosition(
        world: WorldId,
        pos: Vec3,
    ): Result<Unit, BlockGatewayError> {
        store[world]?.remove(pos)
        return Result.success(Unit)
    }

    override fun deletePositions(
        world: WorldId,
        positions: Collection<Vec3>,
    ): Result<Unit, BlockGatewayError> {
        val bucket = store[world] ?: return Result.success(Unit)
        positions.forEach { bucket.remove(it) }
        return Result.success(Unit)
    }
}
