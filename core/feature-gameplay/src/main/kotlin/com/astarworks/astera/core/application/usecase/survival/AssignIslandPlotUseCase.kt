package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository
import com.astarworks.astera.core.application.port.outbound.survival.IIslandTemplateRegistry
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.survival.IslandPlot
import com.astarworks.astera.core.domain.survival.PlotId

/**
 * Allocate a fresh [IslandPlot] to [ownerId] on [islandId] (Wave
 * 5pve-survival / ADR-0107).
 *
 * Free-region search is intentionally trivial in foundation: the use case
 * lays plots out in a single row along +X starting from the template's
 * spawn offset, separated by [PLOT_GAP]. The Wave 6 Paper adapter
 * replaces this with a richer spatial planner (chunk-aligned, grouped per
 * party member, etc.); the in-memory default is deterministic and
 * adequate for tests + early Phase 3 demos.
 *
 * Rules:
 *  - Island must exist.
 *  - Caller must be a member of the owning party.
 *  - Caller must not already own a plot on this island.
 */
public class AssignIslandPlotUseCase(
    private val islandRepository: IPartyIslandRepository,
    private val plotRepository: IIslandPlotRepository,
    private val partyRepository: IPartyRepository,
    private val templateRegistry: IIslandTemplateRegistry,
) {

    public fun assignPlot(
        islandId: IslandId,
        ownerId: PlayerId,
    ): Result<IslandPlot, PlotError> {
        val island = islandRepository.find(islandId)
            ?: return Result.failure(PlotError.IslandNotFound(islandId))

        val party = partyRepository.find(island.partyId)
        if (party != null && ownerId !in party.members) {
            return Result.failure(PlotError.NotAMember(islandId, ownerId))
        }

        plotRepository.forOwner(islandId, ownerId)?.let { existing ->
            return Result.failure(PlotError.AlreadyOwnsPlot(islandId, ownerId, existing.id))
        }

        val template = templateRegistry.find(island.templateId)
        val plotSize = template?.defaultPlotSize ?: DEFAULT_PLOT_SIZE
        val anchor = template?.spawnOffset ?: Vec3.ZERO
        val existing = plotRepository.byIsland(islandId)
        val nextIndex = existing.size
        val origin = Vec3(
            anchor.x + (plotSize.x + PLOT_GAP) * nextIndex,
            anchor.y,
            anchor.z,
        )
        val region = Region(
            min = origin,
            max = Vec3(origin.x + plotSize.x, origin.y + plotSize.y, origin.z + plotSize.z),
        )
        val plot = IslandPlot(
            id = PlotId.random(),
            islandId = islandId,
            ownerId = ownerId,
            regionBounds = region,
            buildAllowed = setOf(ownerId),
        )
        plotRepository.upsert(plot)
        return Result.success(plot)
    }

    public companion object {
        /** Gap between adjacent plots on the +X axis (blocks). */
        public const val PLOT_GAP: Double = 4.0

        /** Used when no template default is registered (test path). */
        public val DEFAULT_PLOT_SIZE: Vec3 = Vec3(16.0, 64.0, 16.0)
    }
}
