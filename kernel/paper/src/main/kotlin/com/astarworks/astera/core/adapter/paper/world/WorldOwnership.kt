package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import java.util.logging.Level
import java.util.logging.Logger

/** Kept enabled in production too: a rejected write must appear in the Folia log gate. */
public fun assertWorldOwnership(scheduler: IScheduler, scope: ExecutionScope) {
    if (scheduler.isOnScope(scope)) return
    val caller = IllegalStateException("World access off owning region: $scope")
    Logger.getLogger("Astera.WorldOwnership").log(Level.SEVERE, caller.message, caller)
    throw caller
}
