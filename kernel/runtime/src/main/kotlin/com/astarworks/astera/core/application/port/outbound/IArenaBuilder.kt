package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.stage.StageId

/**
 * Outbound port: puts a stage's sky-island layout ([com.astarworks.astera.core.domain.stage.ArenaLayout]) into
 * its world (ADR-0246). The Paper adapter builds into the void `arenas` world; the in-memory default does
 * nothing (the simulator has no blocks to place).
 */
public interface IArenaBuilder {

    /**
     * Makes sure [stageId]'s arena stands in its world: builds it when it was never built or its layout
     * changed since (versioned marker), otherwise a no-op. Stages without a layout are always "built".
     * Returns `false` while planning/placement/repair is pending or when the build failed.
     * Callers must wait/retry before admitting players; this never forces a synchronous large build.
     */
    public fun ensureBuilt(stageId: StageId): Boolean

    /** Shortly after a match: re-places island blocks that players destroyed (objective cells excluded). */
    public fun repairLater(stageId: StageId)
}
