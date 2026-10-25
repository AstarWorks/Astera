package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId

/** Slot-backed geometry. Claims run on Global; reads are thread safe. */
public interface IArenaBuilder {
    public fun acquire(stage: Stage): Result<Stage, ArenaAllocationError>
    public fun resolve(stageId: StageId): Stage?
    public fun ensureBuilt(stageId: StageId): Boolean
    public fun repairLater(stageId: StageId)
}

public sealed interface ArenaAllocationError {
    public data object NotReady : ArenaAllocationError
    public data object WrongScope : ArenaAllocationError
}
