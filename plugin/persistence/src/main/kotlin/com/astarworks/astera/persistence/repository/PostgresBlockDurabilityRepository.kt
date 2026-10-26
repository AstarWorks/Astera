package com.astarworks.astera.persistence.repository

import com.astarworks.astera.persistence.adapter.io.blockingTransaction
import com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.persistence.table.BlockDurabilityTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * Postgres-backed [IBlockDurabilityRepository] (Wave 5x, ADR-0090, V20).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default `InMemoryBlockDurabilityRepository` (priority 0)
 * installed by `AsteraCorePlugin`.
 *
 * # Storage model
 *
 * One row per `(world_id, pos_x, pos_y, pos_z)` — Vec3's doubles get
 * truncated to INT at write time because block coordinates are integral.
 * [upsertAll] uses delete-then-insert per row inside a single Exposed
 * transaction (simpler than two-step upsert; the row count per flush is
 * bounded by the number of actively-damaged custom blocks, which stays
 * small for a PoC server).
 *
 * # Wave _persist-1 alignment
 *
 * Mirrors the priority-100 overlay pattern of every other Postgres
 * repository registered in `AsteraPersistencePlugin` (ADR-0068). When
 * `ASTERA_PG_URL` is unset, the in-memory fallback continues to satisfy
 * the port — custom blocks just lose durability across restart.
 */
public class PostgresBlockDurabilityRepository(private val db: Database) : IBlockDurabilityRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    internal fun worlds(): Set<WorldId> = blockingTransaction(db) {
        BlockDurabilityTable.selectAll().map { WorldId(it[BlockDurabilityTable.worldId]) }.toSet()
    }

    override fun loadAll(world: WorldId): Result<Map<Vec3, BlockState>, BlockGatewayError> {
        return try {
            val rows = blockingTransaction(db) {
                BlockDurabilityTable
                    .selectAll()
                    .where { BlockDurabilityTable.worldId eq world.value }
                    .toList()
            }
            val map = rows.associate { row ->
                val pos = Vec3(
                    x = row[BlockDurabilityTable.posX].toDouble(),
                    y = row[BlockDurabilityTable.posY].toDouble(),
                    z = row[BlockDurabilityTable.posZ].toDouble(),
                )
                val state = BlockState(
                    blockType = BlockType(row[BlockDurabilityTable.blockType]),
                    durability = row[BlockDurabilityTable.currentHp],
                    lastDamagedTick = row[BlockDurabilityTable.lastDamagedTick],
                    damagedBy = row[BlockDurabilityTable.damagedBy]?.let { PlayerId(it) },
                    maxHp = row[BlockDurabilityTable.maxHp],
                )
                pos to state
            }
            Result.success(map)
        } catch (ex: SQLException) {
            log.warn("loadAll SQL failure for world={}", world.value, ex)
            Result.failure(BlockGatewayError.Storage(ex.message ?: "SQL failure"))
        }
    }

    override fun upsertAll(
        world: WorldId,
        states: Map<Vec3, BlockState>,
    ): Result<Unit, BlockGatewayError> {
        if (states.isEmpty()) return Result.success(Unit)
        return try {
            blockingTransaction(db) {
                for ((pos, state) in states) {
                    val px = pos.x.toInt()
                    val py = pos.y.toInt()
                    val pz = pos.z.toInt()
                    // Try update first; if zero rows touched, insert. Cheaper than
                    // SELECT-then-decide and works without ON CONFLICT (Exposed v1
                    // upserts vary by dialect).
                    val updated = BlockDurabilityTable.update(
                        {
                            (BlockDurabilityTable.worldId eq world.value) and
                                (BlockDurabilityTable.posX eq px) and
                                (BlockDurabilityTable.posY eq py) and
                                (BlockDurabilityTable.posZ eq pz)
                        },
                    ) {
                        it[blockType] = state.blockType.key
                        it[currentHp] = state.durability
                        it[maxHp] = state.maxHp
                        it[lastDamagedTick] = state.lastDamagedTick
                        it[damagedBy] = state.damagedBy?.uuid
                    }
                    if (updated == 0) {
                        BlockDurabilityTable.insert {
                            it[worldId] = world.value
                            it[posX] = px
                            it[posY] = py
                            it[posZ] = pz
                            it[blockType] = state.blockType.key
                            it[currentHp] = state.durability
                            it[maxHp] = state.maxHp
                            it[lastDamagedTick] = state.lastDamagedTick
                            it[damagedBy] = state.damagedBy?.uuid
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (ex: SQLException) {
            log.warn("upsertAll SQL failure for world={} count={}", world.value, states.size, ex)
            Result.failure(BlockGatewayError.Storage(ex.message ?: "SQL failure"))
        }
    }

    override fun deletePosition(
        world: WorldId,
        pos: Vec3,
    ): Result<Unit, BlockGatewayError> {
        return try {
            blockingTransaction(db) {
                BlockDurabilityTable.deleteWhere {
                    (worldId eq world.value) and
                        (posX eq pos.x.toInt()) and
                        (posY eq pos.y.toInt()) and
                        (posZ eq pos.z.toInt())
                }
            }
            Result.success(Unit)
        } catch (ex: SQLException) {
            log.warn("deletePosition SQL failure for world={} pos={}", world.value, pos, ex)
            Result.failure(BlockGatewayError.Storage(ex.message ?: "SQL failure"))
        }
    }

    override fun deletePositions(
        world: WorldId,
        positions: Collection<Vec3>,
    ): Result<Unit, BlockGatewayError> {
        if (positions.isEmpty()) return Result.success(Unit)
        return try {
            // Single-statement DELETE keyed by (x, y, z) triples. Each position
            // hits the composite PK so cardinality is bounded by the caller's
            // batch size.
            blockingTransaction(db) {
                positions.forEach { pos ->
                    BlockDurabilityTable.deleteWhere {
                        (worldId eq world.value) and
                            (posX eq pos.x.toInt()) and
                            (posY eq pos.y.toInt()) and
                            (posZ eq pos.z.toInt())
                    }
                }
            }
            Result.success(Unit)
        } catch (ex: SQLException) {
            log.warn(
                "deletePositions SQL failure for world={} count={}",
                world.value,
                positions.size,
                ex,
            )
            Result.failure(BlockGatewayError.Storage(ex.message ?: "SQL failure"))
        }
    }

}
