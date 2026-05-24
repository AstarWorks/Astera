package com.astarworks.astera.persistence.repository.survival

import com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.survival.IslandPlot
import com.astarworks.astera.core.domain.survival.PlotId
import com.astarworks.astera.persistence.jsonb.IslandPlotAllowedJson
import com.astarworks.astera.persistence.table.survival.IslandPlotsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IIslandPlotRepository] (Wave 5pve-survival / ADR-0107).
 */
public class PostgresIslandPlotRepository(private val db: Database) : IIslandPlotRepository {

    override fun find(id: PlotId): IslandPlot? = transaction(db) {
        IslandPlotsTable
            .selectAll()
            .where { IslandPlotsTable.plotId eq id.value }
            .firstOrNull()
            ?.toPlot()
    }

    override fun forOwner(islandId: IslandId, ownerId: PlayerId): IslandPlot? = transaction(db) {
        IslandPlotsTable
            .selectAll()
            .where { (IslandPlotsTable.islandId eq islandId.value) and (IslandPlotsTable.ownerId eq ownerId.uuid) }
            .firstOrNull()
            ?.toPlot()
    }

    override fun byIsland(islandId: IslandId): List<IslandPlot> = transaction(db) {
        IslandPlotsTable
            .selectAll()
            .where { IslandPlotsTable.islandId eq islandId.value }
            .mapNotNull { it.toPlot() }
    }

    override fun upsert(plot: IslandPlot) {
        transaction(db) {
            IslandPlotsTable.upsert(IslandPlotsTable.plotId) {
                it[plotId] = plot.id.value
                it[islandId] = plot.islandId.value
                it[ownerId] = plot.ownerId.uuid
                it[minX] = plot.regionBounds.min.x
                it[minY] = plot.regionBounds.min.y
                it[minZ] = plot.regionBounds.min.z
                it[maxX] = plot.regionBounds.max.x
                it[maxY] = plot.regionBounds.max.y
                it[maxZ] = plot.regionBounds.max.z
                it[buildAllowed] = IslandPlotAllowedJson.fromUuids(plot.buildAllowed.map { p -> p.uuid })
            }
        }
    }

    override fun remove(id: PlotId) {
        transaction(db) {
            IslandPlotsTable.deleteWhere { IslandPlotsTable.plotId eq id.value }
        }
    }

    private fun ResultRow.toPlot(): IslandPlot? {
        val allowed = this[IslandPlotsTable.buildAllowed].playerIdsAsUuids().mapTo(mutableSetOf()) { PlayerId(it) }
        val ownerId = PlayerId(this[IslandPlotsTable.ownerId])
        // Defensive: corrupt rows (owner not in allow-list) skipped rather
        // than crashing the caller — IslandPlot enforces this in its init {}.
        if (ownerId !in allowed) return null
        val region = Region(
            min = Vec3(
                this[IslandPlotsTable.minX],
                this[IslandPlotsTable.minY],
                this[IslandPlotsTable.minZ],
            ),
            max = Vec3(
                this[IslandPlotsTable.maxX],
                this[IslandPlotsTable.maxY],
                this[IslandPlotsTable.maxZ],
            ),
        )
        return IslandPlot(
            id = PlotId(this[IslandPlotsTable.plotId]),
            islandId = IslandId(this[IslandPlotsTable.islandId]),
            ownerId = ownerId,
            regionBounds = region,
            buildAllowed = allowed,
        )
    }
}
