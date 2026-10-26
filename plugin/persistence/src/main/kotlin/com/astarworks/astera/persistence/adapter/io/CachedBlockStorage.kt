package com.astarworks.astera.persistence.adapter.io

import com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository
import com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.*
import com.astarworks.astera.persistence.repository.PostgresBlockChangeJournalRepository
import com.astarworks.astera.persistence.repository.PostgresBlockDurabilityRepository
import java.util.concurrent.ConcurrentHashMap

public class CachedBlockStorage(
    private val journalDb: PostgresBlockChangeJournalRepository,
    private val durabilityDb: PostgresBlockDurabilityRepository,
    private val writes: WriteBehind,
) {
    private val states = ConcurrentHashMap<BlockKey, BlockState>()
    private val durability = ConcurrentHashMap<Pair<WorldId, Vec3>, BlockState>()

    public fun preload() {
        journalDb.worlds().forEach { world ->
            when (val result = journalDb.loadAll(world)) {
                is Result.Success -> states.putAll(result.value)
                is Result.Failure -> error("Journal preload failed: ${result.error}")
            }
        }
        durabilityDb.worlds().forEach { world ->
            when (val result = durabilityDb.loadAll(world)) {
                is Result.Success -> result.value.forEach { (pos, state) -> durability[world to pos] = state }
                is Result.Failure -> error("Durability preload failed: ${result.error}")
            }
        }
    }

    public val journal: IBlockChangeJournalRepository = object : IBlockChangeJournalRepository {
        override fun save(key: BlockKey, state: BlockState): Result<Unit, JournalError> {
            states[key] = state
            writes.enqueue("block-journal" to key) { checkSuccess(journalDb.save(key, state)) }
            return Result.success(Unit)
        }
        override fun load(key: BlockKey): Result<BlockState?, JournalError> = Result.success(states[key])
        override fun loadAll(world: WorldId): Result<Map<BlockKey, BlockState>, JournalError> =
            Result.success(states.filterKeys { it.worldId == world.value })
        override fun delete(key: BlockKey): Result<Unit, JournalError> {
            states.remove(key)
            writes.enqueue("block-journal" to key) { checkSuccess(journalDb.delete(key)) }
            return Result.success(Unit)
        }
    }
    public val blocks: IBlockDurabilityRepository = object : IBlockDurabilityRepository {
        override fun loadAll(world: WorldId): Result<Map<Vec3, BlockState>, BlockGatewayError> =
            Result.success(durability.filterKeys { it.first == world }.mapKeys { it.key.second })
        override fun upsertAll(world: WorldId, states: Map<Vec3, BlockState>): Result<Unit, BlockGatewayError> {
            states.forEach { (pos, state) ->
                durability[world to pos] = state
                writes.enqueue(Triple("durability", world, pos)) { checkSuccess(durabilityDb.upsertAll(world, mapOf(pos to state))) }
            }
            return Result.success(Unit)
        }
        override fun deletePosition(world: WorldId, pos: Vec3): Result<Unit, BlockGatewayError> {
            durability.remove(world to pos)
            writes.enqueue(Triple("durability", world, pos)) { checkSuccess(durabilityDb.deletePosition(world, pos)) }
            return Result.success(Unit)
        }
        override fun deletePositions(world: WorldId, positions: Collection<Vec3>): Result<Unit, BlockGatewayError> {
            positions.forEach { deletePosition(world, it) }
            return Result.success(Unit)
        }
    }
    private fun checkSuccess(result: Result<Unit, *>) {
        check(result is Result.Success) { "Block persistence failed: $result" }
    }
}
