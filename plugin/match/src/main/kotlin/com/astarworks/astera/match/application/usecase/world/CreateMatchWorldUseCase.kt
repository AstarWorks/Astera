package com.astarworks.astera.match.application.usecase.world

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IArenaBuilder
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.domain.match.MatchWorld
import org.slf4j.LoggerFactory

/**
 * Use case (Wave 5d, reshaped by ADR-0246): on `MatchStarted`, make sure the match's stage exists as blocks in
 * its world ([IArenaBuilder.ensureBuilt] is idempotent: a versioned marker decides whether anything is placed)
 * and record the [MatchWorld] (the stage's real world) via [IMatchWorldRepository].
 *
 * Players are teleported after `MatchStarted` (entry runs after the match is created), so the island they
 * land on is built by then. A stage without a layout is hand-built: nothing to do but the bookkeeping.
 *
 * Soft-fail: when the arena cannot be built the match still starts (logged), so a broken world never blocks
 * the queue; the e2e playthrough suites assert the island is really there.
 */
public class CreateMatchWorldUseCase(
    private val stages: IStageRegistry,
    private val arenas: IArenaBuilder,
    private val matchWorlds: IMatchWorldRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Subscribe to [MatchStarted] events. Returns the subscription so the caller can cancel it. */
    public fun subscribe(bus: IDomainEventBus): IDomainEventBus.Subscription =
        bus.subscribe(MatchStarted::class, EventDelivery.Global) { event -> handle(event) }

    /** Visible for tests; exercised through [subscribe] in production. */
    public fun handle(event: MatchStarted) {
        val stage = stages.find(event.stageId)
        if (stage == null) {
            log.warn("MatchWorld for {}: stage {} is not registered; nothing to build", event.matchId, event.stageId)
            return
        }
        if (!arenas.ensureBuilt(stage.id)) {
            log.warn("MatchWorld for {}: arena of stage {} could not be built; the match runs without it", event.matchId, stage.id)
        }
        matchWorlds.save(MatchWorld(event.matchId, stage.world, stage.id))
    }
}
