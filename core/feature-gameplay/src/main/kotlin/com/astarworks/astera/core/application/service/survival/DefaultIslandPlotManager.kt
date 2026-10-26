package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotManager
import com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository
import com.astarworks.astera.core.application.usecase.survival.AssignIslandPlotUseCase
import com.astarworks.astera.core.application.usecase.survival.PlotError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.IslandPlot
import com.astarworks.astera.core.domain.survival.PlotId

/**
 * Default [IIslandPlotManager] (Wave 5pve-survival / ADR-0107).
 *
 * Composes [AssignIslandPlotUseCase] (allocation rules) with direct
 * [IIslandPlotRepository] reads (build / list queries). canBuild walks
 * every plot on the island — Phase 1 acceptable because per-island plot
 * count ≤ maxMembers (≤ 10); Wave 6 spatial-index-backed variant lands
 * with the Paper adapter.
 */
public class DefaultIslandPlotManager(
    private val plotRepository: IIslandPlotRepository,
    private val assignUseCase: AssignIslandPlotUseCase,
) : IIslandPlotManager {

    override suspend fun assignPlot(islandId: IslandId, ownerId: PlayerId): Result<IslandPlot, PlotError> =
        assignUseCase.assignPlot(islandId, ownerId)

    override suspend fun canBuild(islandId: IslandId, playerId: PlayerId, position: Vec3): Boolean {
        val plot = plotRepository.byIsland(islandId).firstOrNull {
            it.regionBounds.contains(position)
        } ?: return true // No plot covers this position — default-allow.
        return plot.canBuild(playerId)
    }

    override suspend fun plotFor(islandId: IslandId, ownerId: PlayerId): IslandPlot? =
        plotRepository.forOwner(islandId, ownerId)

    override suspend fun plotsOn(islandId: IslandId): List<IslandPlot> =
        plotRepository.byIsland(islandId)

    override suspend fun releasePlot(plotId: PlotId) {
        plotRepository.remove(plotId)
    }
}
