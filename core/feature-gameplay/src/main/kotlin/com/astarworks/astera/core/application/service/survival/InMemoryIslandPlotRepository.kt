package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.IslandPlot
import com.astarworks.astera.core.domain.survival.PlotId
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [IIslandPlotRepository] (Wave 5pve-survival /
 * ADR-0107).
 */
public class InMemoryIslandPlotRepository : IIslandPlotRepository {

    private val plots: ConcurrentHashMap<PlotId, IslandPlot> = ConcurrentHashMap()

    override suspend fun find(id: PlotId): IslandPlot? = plots[id]

    override suspend fun forOwner(islandId: IslandId, ownerId: PlayerId): IslandPlot? =
        plots.values.firstOrNull { it.islandId == islandId && it.ownerId == ownerId }

    override suspend fun byIsland(islandId: IslandId): List<IslandPlot> =
        plots.values.filter { it.islandId == islandId }

    override suspend fun upsert(plot: IslandPlot) {
        plots[plot.id] = plot
    }

    override suspend fun remove(id: PlotId) {
        plots.remove(id)
    }
}
