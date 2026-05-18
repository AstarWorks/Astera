package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.BlockChangeJournal
import com.astarworks.astera.core.domain.event.MatchEnded
import org.slf4j.LoggerFactory

/**
 * Wave 5d (ADR-0033): on `MatchEnded`, replay [BlockChangeJournal]'s entries
 * for the ended match in **reverse order** (most-recent first) and call
 * [IBlockStateGateway.restore] for each position so the per-match world is
 * returned to its pre-match state.
 *
 * ## Reverse iteration
 *
 * If a player broke `(x,y,z)` then later placed a new block at the same
 * `(x,y,z)`, replaying forward would `restore` the broken position (good)
 * then `restore` the placed position (also good, since restore is a no-op
 * if nothing is tracked there). Replaying *reverse* makes the intent
 * explicit: undo the most recent change first, then walk back through the
 * history. For the simple PoC (restore-only) the order is observationally
 * equivalent, but the reverse-replay convention is what a future
 * track-aware restoration (re-tracking `prevBlockType` per Broken entry)
 * will require.
 *
 * ## No MatchWorld → soft-fail
 *
 * If [IMatchWorldRepository.findByMatch] returns `null` the use case logs
 * at INFO and skips gateway calls — typical for matches that ended before
 * `CreateMatchWorldUseCase` populated a world entry, or for unit-test
 * matches that never had per-match world isolation. The journal is still
 * cleared so the use case is idempotent under replay.
 *
 * ## Wiring
 *
 * Phase C wires `subscribe(bus)` in `AsteraMatchPlugin.onEnable`; the
 * returned subscription is cancelled on `onDisable` for clean unload.
 */
public class RestoreBlocksOnMatchEndedUseCase(
    private val gateway: IBlockStateGateway,
    private val journal: BlockChangeJournal,
    private val matchWorlds: IMatchWorldRepository,
) {

    private val logger = LoggerFactory.getLogger(RestoreBlocksOnMatchEndedUseCase::class.java)

    /** Subscribe to [MatchEnded]. Returns the subscription for Phase C teardown. */
    public fun subscribe(bus: IDomainEventBus): IDomainEventBus.Subscription =
        bus.subscribe(MatchEnded::class) { event -> handle(event) }

    /** Visible for tests; exercised through [subscribe] in production. */
    public fun handle(event: MatchEnded) {
        val matchWorld = matchWorlds.findByMatch(event.matchId)
        if (matchWorld == null) {
            logger.info(
                "RestoreBlocksOnMatchEnded: no MatchWorld for match {}; skipping restoration",
                event.matchId,
            )
            journal.clear(event.matchId)
            return
        }

        val changes = journal.snapshot(event.matchId)
        for (change in changes.asReversed()) {
            gateway.restore(matchWorld.worldId, change.position)
        }
        logger.info(
            "RestoreBlocksOnMatchEnded: restored {} block change(s) for match {} in world {}",
            changes.size,
            event.matchId,
            matchWorld.worldId,
        )
        journal.clear(event.matchId)
    }
}
