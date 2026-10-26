package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.game.InstanceId
import java.util.UUID

/** Global is the single writer. Readers see an immutable snapshot (ADR-0253). */
public class InstanceDirectory(private val scheduler: IScheduler) {
    @Volatile private var players: Map<UUID, InstanceId> = emptyMap()

    public fun find(player: UUID): InstanceId? = players[player]
    public fun snapshot(): Map<UUID, InstanceId> = players

    public fun admit(player: UUID, instance: InstanceId) {
        check(scheduler.isOnScope(ExecutionScope.Global))
        players = players + (player to instance)
    }

    public fun remove(player: UUID) {
        check(scheduler.isOnScope(ExecutionScope.Global))
        players = players - player
    }

    public fun removeInstance(instance: InstanceId) {
        check(scheduler.isOnScope(ExecutionScope.Global))
        players = players.filterValues { it != instance }
    }
}
