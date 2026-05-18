package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.event.BlockBroken
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.BreakCore

/**
 * BreakCore-mode listener (Wave 5c).
 *
 * Subscribes to [BlockBroken]. When the broken block's id equals the mode's
 * [BreakCore.coreBlockId] and the broken block belongs to an in-progress
 * match, the listener publishes [MatchEnded] with
 * [EndReason.ObjectiveCompleted].
 *
 * ## Winning-team semantics
 *
 * BreakCore wants "the team that does **not** own the destroyed core wins".
 * Region-to-team ownership lookup isn't available yet (lands in Wave 5d/6
 * once stage regions carry team-affinity metadata), so for the PoC we publish
 * `winningTeam = null` and document the gap. The downstream UI today shows a
 * "draw" / "objective met" panel, which is acceptable per the Wave 5c plan.
 *
 * ## Lifecycle integration
 *
 * If [lifecycle] is non-null, the listener routes the end through
 * [IMatchLifecycle.end] so the state machine transitions cleanly (ACTIVE →
 * ENDED) and the canonical MatchEnded is fired by [EndMatchUseCase]. If
 * [lifecycle] is null (e.g. early-Phase-C wiring, unit tests), the listener
 * falls back to publishing [MatchEnded] directly — the state machine will
 * remain on the previous phase, which is acceptable for the Wave 5c PoC.
 *
 * Why allow both paths? Phase B can't reach into the use-case wiring graph
 * (which is Phase C). Letting the listener publish directly keeps Phase B
 * tests independent of `IMatchLifecycle`. Phase C wires lifecycle in for
 * production.
 */
public class BreakCoreListener(
    private val eventBus: IDomainEventBus,
    private val matches: IMatchRepository,
    private val lifecycle: IMatchLifecycle?,
    private val mode: BreakCore,
) {

    /** Subscribe to [BlockBroken]. Returns the subscription for Phase C teardown. */
    public fun subscribe(): IDomainEventBus.Subscription =
        eventBus.subscribe(BlockBroken::class) { event -> handle(event) }

    /** The mode value this listener is bound to (Phase C wiring uses this). */
    public fun mode(): BreakCore = mode

    private fun handle(event: BlockBroken) {
        if (event.blockId != mode.coreBlockId) return

        val match = matches.findById(event.matchId) ?: return
        if (match.phase == MatchPhase.ENDED) return

        if (lifecycle != null) {
            lifecycle.end(event.matchId, EndReason.ObjectiveCompleted)
        } else {
            eventBus.publish(
                MatchEnded(
                    matchId = event.matchId,
                    winningTeam = null, // region-team mapping arrives in Wave 5d/6
                    atTick = event.atTick,
                    reason = EndReason.ObjectiveCompleted,
                ),
            )
        }
    }
}
