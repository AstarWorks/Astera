package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.IslandPlot
import com.astarworks.astera.core.domain.survival.PlotId
import com.astarworks.astera.core.application.usecase.survival.PlotError

/**
 * Outbound port: per-member plot allocator (Wave 5pve-survival / ADR-0107).
 *
 * Wraps [IIslandPlotRepository] with the policy decisions that gate
 * `assignPlot` (one plot per member, free-region search). Use cases call
 * this rather than the repository directly so the rules live next to the
 * data they govern.
 */
public interface IIslandPlotManager {

    /**
     * Allocate a new plot to [ownerId] on [islandId]. Fails if the
     * island is unknown, the player is not a party member, the player
     * already owns a plot, or no free region is available.
     */
    public fun assignPlot(islandId: IslandId, ownerId: PlayerId): Result<IslandPlot, PlotError>

    /**
     * `true` if [playerId] may modify blocks at [position] inside any
     * plot on [islandId]. Returns `true` outside every plot (the
     * region-permission service handles default-allow elsewhere).
     */
    public fun canBuild(islandId: IslandId, playerId: PlayerId, position: Vec3): Boolean

    /** Plot for [ownerId] on [islandId], if any. */
    public fun plotFor(islandId: IslandId, ownerId: PlayerId): IslandPlot?

    /** All plots currently allocated on [islandId]. */
    public fun plotsOn(islandId: IslandId): List<IslandPlot>

    /** Drop the plot — typically when its owner leaves the party. */
    public fun releasePlot(plotId: PlotId)
}
