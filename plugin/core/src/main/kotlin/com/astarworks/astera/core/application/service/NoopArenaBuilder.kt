package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IArenaBuilder
import com.astarworks.astera.core.domain.stage.StageId

/** Default [IArenaBuilder] for hosts without blocks (simulator, tests): every arena counts as built. */
public class NoopArenaBuilder : IArenaBuilder {
    override fun ensureBuilt(stageId: StageId): Boolean = true

    override fun repairLater(stageId: StageId) {
    }
}
