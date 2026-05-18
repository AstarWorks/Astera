package com.astarworks.astera.match.application.usecase.world

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.core.domain.event.MatchEnded

/**
 * Use case (Wave 5d): on `MatchEnded`, remove the per-match [MatchWorld]
 * entry from [IMatchWorldRepository].
 *
 * Phase 1 PoC scope: repository cleanup only. Wave 6+ may extend this to
 * unload / delete the physical Bukkit world directory via [IWorldGateway]
 * (or a dedicated `IWorldDirManager` port) once `PaperSchematicLoader` is
 * in place and per-match worlds are real on-disk Bukkit worlds rather than
 * symbolic references.
 *
 * Mirrors `CreateMatchWorldUseCase`'s subscribe pattern; the Phase C wiring
 * calls [subscribe] once at `onEnable` and cancels the returned
 * subscription at `onDisable`.
 */
public class DestroyMatchWorldUseCase(
    private val matchWorlds: IMatchWorldRepository,
) {

    /**
     * Subscribe to [MatchEnded] events. Returns the subscription so the
     * caller (Phase C `AsteraMatchPlugin.onDisable`) can cancel it.
     */
    public fun subscribe(bus: IDomainEventBus): IDomainEventBus.Subscription =
        bus.subscribe(MatchEnded::class) { event -> handle(event) }

    /** Visible for tests; exercised through [subscribe] in production. */
    public fun handle(event: MatchEnded) {
        matchWorlds.remove(event.matchId)
    }
}
