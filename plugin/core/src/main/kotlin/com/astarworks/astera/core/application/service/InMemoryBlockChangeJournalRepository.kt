package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.BlockKey
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.JournalError
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory default [IBlockChangeJournalRepository] (Wave 6c, ADR-0160).
 *
 * Backed by a single [ConcurrentHashMap] keyed on [BlockKey]. Identical
 * semantics to the pre-6c `ConcurrentHashMap` tracker that lived inside
 * `PaperBlockStateGateway`: process-local, wiped on `/restart`. Operators
 * who set `ASTERA_PG_URL` get [com.astarworks.astera.persistence.repository.PostgresBlockChangeJournalRepository]
 * registered at priority 100 by `AsteraPersistencePlugin`, which overlays
 * this default and adds the cross-restart durability guarantee.
 *
 * # Why core ships this
 *
 * Vanilla / no-Postgres operators must still get a usable Astera. The
 * gateway calls [save] / [delete] unconditionally; it does not branch on
 * "is the journal Postgres-backed?". This default keeps the gateway code
 * branch-free.
 */
public class InMemoryBlockChangeJournalRepository : IBlockChangeJournalRepository {

    private val store: MutableMap<BlockKey, BlockState> = ConcurrentHashMap()

    override fun save(key: BlockKey, state: BlockState): Result<Unit, JournalError> {
        store[key] = state
        return Result.success(Unit)
    }

    override fun load(key: BlockKey): Result<BlockState?, JournalError> =
        Result.success(store[key])

    override fun loadAll(world: WorldId): Result<Map<BlockKey, BlockState>, JournalError> {
        val matching = store.filterKeys { it.worldId == world.value }
        return Result.success(matching)
    }

    override fun delete(key: BlockKey): Result<Unit, JournalError> {
        store.remove(key)
        return Result.success(Unit)
    }
}
