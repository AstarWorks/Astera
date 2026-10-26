package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId

/** Slot-backed geometry. Claims run on Global; reads are thread safe. */
public interface IArenaBuilder {
    public fun acquire(stage: Stage): Result<Stage, ArenaAllocationError>
    public fun resolve(stageId: StageId): Stage?

    /**
     * The placed stage (slot-shifted spawns) that the next [acquire] of [stage] will claim, or `null` while no
     * completed build of it exists. Read-only and thread safe: callers use it to learn WHERE the arena stands and
     * WHETHER it is ready before putting players on it (a template's own coordinates hold no blocks).
     * Stages without a layout need no build and are returned as they are.
     */
    public fun peekReady(stage: Stage): Stage?

    /**
     * Makes sure [stageId]'s arena stands in its world: builds it when it was never built or its layout
     * changed since (versioned marker), otherwise a no-op. Stages without a layout are always "built".
     * Returns `false` while planning/placement/repair is pending or when the build failed.
     * Callers must wait/retry before admitting players; this never forces a synchronous large build.
     */
    public fun ensureBuilt(stageId: StageId): Boolean
    public fun repairLater(stageId: StageId)
}

public sealed interface ArenaAllocationError {
    public data object NotReady : ArenaAllocationError
    public data object WrongScope : ArenaAllocationError
}
