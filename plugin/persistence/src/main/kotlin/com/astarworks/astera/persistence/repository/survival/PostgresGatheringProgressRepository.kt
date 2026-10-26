package com.astarworks.astera.persistence.repository.survival

import com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.GatheringProgress
import com.astarworks.astera.core.domain.survival.GatheringSpotId
import com.astarworks.astera.persistence.table.survival.GatheringProgressTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IGatheringProgressRepository] (Wave 5pve-survival /
 * ADR-0107). Backed by the composite-PK `(player_id, spot_id)` table; upsert
 * keys on the PK pair so concurrent harvests collapse to one row regardless
 * of insertion order.
 */
public class PostgresGatheringProgressRepository(private val db: Database) : IGatheringProgressRepository {

    override suspend fun find(playerId: PlayerId, spotId: GatheringSpotId): GatheringProgress? = ioTransaction(db) {
        GatheringProgressTable
            .selectAll()
            .where { (GatheringProgressTable.playerId eq playerId.uuid) and (GatheringProgressTable.spotId eq spotId.value) }
            .firstOrNull()
            ?.toProgress()
    }

    override suspend fun forPlayer(playerId: PlayerId): List<GatheringProgress> = ioTransaction(db) {
        GatheringProgressTable
            .selectAll()
            .where { GatheringProgressTable.playerId eq playerId.uuid }
            .map { it.toProgress() }
    }

    override suspend fun upsert(progress: GatheringProgress) {
        ioTransaction(db) {
            GatheringProgressTable.upsert(
                GatheringProgressTable.playerId,
                GatheringProgressTable.spotId,
            ) {
                it[playerId] = progress.playerId.uuid
                it[spotId] = progress.spotId.value
                it[lastHarvestedAt] = progress.lastHarvestedAt
                it[totalHarvests] = progress.totalHarvests
            }
        }
    }

    private fun ResultRow.toProgress(): GatheringProgress = GatheringProgress(
        playerId = PlayerId(this[GatheringProgressTable.playerId]),
        spotId = GatheringSpotId(this[GatheringProgressTable.spotId]),
        lastHarvestedAt = this[GatheringProgressTable.lastHarvestedAt],
        totalHarvests = this[GatheringProgressTable.totalHarvests],
    )
}
