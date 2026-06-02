package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockKey
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.JournalError
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.persistence.table.BlockChangeJournalTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

/**
 * Postgres-backed [IBlockChangeJournalRepository] (Wave 6c, ADR-0160, V45).
 *
 * Registered by `AsteraPersistencePlugin` at priority 100; overlays the
 * core-default `InMemoryBlockChangeJournalRepository` (priority 0). When
 * `ASTERA_PG_URL` is unset, the in-memory fallback continues to satisfy
 * the port — block damage just loses durability across restart, matching
 * the pre-6c gateway behaviour.
 *
 * # Wire format
 *
 * `BlockState` is encoded to a JSON string via a kotlinx-serialization
 * adapter type `BlockStateJson` (private to this file). The adapter
 * mirrors `BlockState`'s public fields one-for-one but is `@Serializable`
 * so kotlinx can drive the codec — Konsist rule #6 forbids putting the
 * annotation on `core.domain.world.BlockState` itself. Cross-version
 * field additions can be absorbed by widening `BlockStateJson` (the
 * unknown-field decode policy is `ignoreUnknownKeys = true` via the
 * project's default [Json] config).
 *
 * # Upsert pattern
 *
 * Update-then-insert per row inside a single Exposed transaction. Cheaper
 * than `INSERT ... ON CONFLICT` for the unbounded dialect surface and
 * mirrors the pattern used by `PostgresBlockDurabilityRepository`. Per-row
 * transaction overhead is acceptable for Phase 1 PoC because save is
 * called once per damage event (bounded by TPS).
 *
 * # Failure handling
 *
 * SQL failures surface as `JournalError.Storage(reason)`; the gateway
 * logs WARN and continues against the in-memory tracker, so a transient
 * Postgres outage does not freeze gameplay.
 */
public class PostgresBlockChangeJournalRepository(
    private val db: Database,
) : IBlockChangeJournalRepository {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json: Json = Json { ignoreUnknownKeys = true }

    override fun save(key: BlockKey, state: BlockState): Result<Unit, JournalError> {
        return try {
            val payload = json.encodeToString(BlockStateJson.serializer(), state.toJson())
            transaction(db) {
                val updated = BlockChangeJournalTable.update(
                    {
                        (BlockChangeJournalTable.worldId eq key.worldId) and
                            (BlockChangeJournalTable.posX eq key.x) and
                            (BlockChangeJournalTable.posY eq key.y) and
                            (BlockChangeJournalTable.posZ eq key.z)
                    },
                ) {
                    it[BlockChangeJournalTable.state] = payload
                }
                if (updated == 0) {
                    BlockChangeJournalTable.insert {
                        it[worldId] = key.worldId
                        it[posX] = key.x
                        it[posY] = key.y
                        it[posZ] = key.z
                        it[BlockChangeJournalTable.state] = payload
                    }
                }
            }
            Result.success(Unit)
        } catch (ex: SQLException) {
            log.warn("BlockChangeJournal.save SQL failure for key={}", key, ex)
            Result.failure(JournalError.Storage(ex.message ?: "SQL failure"))
        }
    }

    override fun load(key: BlockKey): Result<BlockState?, JournalError> {
        return try {
            val row = transaction(db) {
                BlockChangeJournalTable
                    .selectAll()
                    .where {
                        (BlockChangeJournalTable.worldId eq key.worldId) and
                            (BlockChangeJournalTable.posX eq key.x) and
                            (BlockChangeJournalTable.posY eq key.y) and
                            (BlockChangeJournalTable.posZ eq key.z)
                    }
                    .firstOrNull()
            }
            val state = row?.get(BlockChangeJournalTable.state)
                ?.let { json.decodeFromString(BlockStateJson.serializer(), it).toDomain() }
            Result.success(state)
        } catch (ex: SQLException) {
            log.warn("BlockChangeJournal.load SQL failure for key={}", key, ex)
            Result.failure(JournalError.Storage(ex.message ?: "SQL failure"))
        }
    }

    override fun loadAll(world: WorldId): Result<Map<BlockKey, BlockState>, JournalError> {
        return try {
            val rows = transaction(db) {
                BlockChangeJournalTable
                    .selectAll()
                    .where { BlockChangeJournalTable.worldId eq world.value }
                    .toList()
            }
            val map = rows.associate { row ->
                val key = BlockKey(
                    worldId = row[BlockChangeJournalTable.worldId],
                    x = row[BlockChangeJournalTable.posX],
                    y = row[BlockChangeJournalTable.posY],
                    z = row[BlockChangeJournalTable.posZ],
                )
                val state = json
                    .decodeFromString(BlockStateJson.serializer(), row[BlockChangeJournalTable.state])
                    .toDomain()
                key to state
            }
            Result.success(map)
        } catch (ex: SQLException) {
            log.warn("BlockChangeJournal.loadAll SQL failure for world={}", world.value, ex)
            Result.failure(JournalError.Storage(ex.message ?: "SQL failure"))
        }
    }

    override fun delete(key: BlockKey): Result<Unit, JournalError> {
        return try {
            transaction(db) {
                BlockChangeJournalTable.deleteWhere {
                    (BlockChangeJournalTable.worldId eq key.worldId) and
                        (BlockChangeJournalTable.posX eq key.x) and
                        (BlockChangeJournalTable.posY eq key.y) and
                        (BlockChangeJournalTable.posZ eq key.z)
                }
            }
            Result.success(Unit)
        } catch (ex: SQLException) {
            log.warn("BlockChangeJournal.delete SQL failure for key={}", key, ex)
            Result.failure(JournalError.Storage(ex.message ?: "SQL failure"))
        }
    }

    /**
     * `@Serializable` mirror of `BlockState`. Konsist rule #6 forbids the
     * annotation on the domain type; this private adapter at the
     * persistence boundary keeps `core.domain.*` pure. Cross-version field
     * additions land here; widen one field, bump the version, and the
     * `ignoreUnknownKeys = true` policy absorbs the boundary.
     */
    @Serializable
    private data class BlockStateJson(
        val blockType: String,
        val durability: Int,
        val lastDamagedTick: Long,
        val damagedBy: String?,
        val maxHp: Int,
    )

    private fun BlockState.toJson(): BlockStateJson = BlockStateJson(
        blockType = blockType.key,
        durability = durability,
        lastDamagedTick = lastDamagedTick,
        damagedBy = damagedBy?.uuid?.toString(),
        maxHp = maxHp,
    )

    private fun BlockStateJson.toDomain(): BlockState = BlockState(
        blockType = BlockType(blockType),
        durability = durability,
        lastDamagedTick = lastDamagedTick,
        damagedBy = damagedBy?.let { PlayerId(UUID.fromString(it)) },
        maxHp = maxHp,
    )
}
