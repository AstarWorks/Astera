package com.astarworks.astera.match.application.usecase.world

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IArenaBuilder
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository

/**
 * Use case (Wave 5d, reshaped by ADR-0246): on `MatchEnded`, remove the
 * [com.astarworks.astera.match.domain.match.MatchWorld] entry from [IMatchWorldRepository] and ask [IArenaBuilder]
 * to re-place the island blocks the match destroyed (the arena is shared by every later match on the same stage).
 *
 * Mirrors `CreateMatchWorldUseCase`'s subscribe pattern; the Phase C wiring
 * calls [subscribe] once at `onEnable` and cancels the returned
 * subscription at `onDisable`.
 */
public class DestroyMatchWorldUseCase(
    private val matchWorlds: IMatchWorldRepository,
    private val arenas: IArenaBuilder,
) {

    /** Subscribe to [MatchEnded] events. Returns the subscription so the caller can cancel it. */
    public fun subscribe(bus: IDomainEventBus): IDomainEventBus.Subscription =
        bus.subscribe(MatchEnded::class, EventDelivery.Global) { event -> handle(event) }

    /** Visible for tests; exercised through [subscribe] in production. */
    public fun handle(event: MatchEnded) {
        val world = matchWorlds.findByMatch(event.matchId)
        matchWorlds.remove(event.matchId)
        world?.let { arenas.repairLater(it.stageId) }
    }
}
