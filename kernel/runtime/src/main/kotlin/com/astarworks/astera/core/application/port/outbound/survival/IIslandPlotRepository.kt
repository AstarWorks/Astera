package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.IslandPlot
import com.astarworks.astera.core.domain.survival.PlotId

/**
 * Outbound port: persistent record of [IslandPlot] aggregates (Wave
 * 5pve-survival / ADR-0107). Backs [IIslandPlotManager].
 */
public interface IIslandPlotRepository {

    public suspend fun find(id: PlotId): IslandPlot?

    /** Plot owned by [ownerId] on [islandId], if any (foundation: 0..1 per pair). */
    public suspend fun forOwner(islandId: IslandId, ownerId: PlayerId): IslandPlot?

    /** All plots on [islandId]. */
    public suspend fun byIsland(islandId: IslandId): List<IslandPlot>

    public suspend fun upsert(plot: IslandPlot)

    public suspend fun remove(id: PlotId)
}
