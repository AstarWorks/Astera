package com.astarworks.astera.match.application.eventbus.listeners.region

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityLeftRegion

/**
 * Derives the match-scoped region transitions the objective modes consume
 * ([EntityEnteredRegion] / [EntityLeftRegion]) straight from [PlayerMoved].
 *
 * Regions are the *named* regions of the match's own stage (`base-red`,
 * `wool-a`, ...), so two stages may reuse names without colliding and no
 * global spatial index has to be kept in sync with the stage registry.
 * Players outside a match are ignored.
 *
 * Stateless on purpose: a move's transition is the difference between the
 * regions at `from` and at `to`. Teleports (respawn, match entry) publish no
 * move, so they can never leave a stale membership behind that would swallow
 * the next real crossing; modes drop a dead player's own state themselves.
 *
 * Single-threaded: runs on the main thread like the bus that feeds it.
 */
public class MatchRegionTransitionListener(
    private val eventBus: IDomainEventBus,
    private val matches: IMatchRepository,
    private val stages: IStageRegistry,
) {

    /** Registers the handler on [eventBus]; cancel the returned subscription to detach. */
    public fun subscribe(): IDomainEventBus.Subscription =
        eventBus.subscribe(PlayerMoved::class) { handle(it) }

    private fun handle(event: PlayerMoved) {
        val match = matches.findMatchOf(event.playerId) ?: return
        val stage = stages.find(match.stageId) ?: return

        val before = stage.namedRegionsAt(event.from)
        val after = stage.namedRegionsAt(event.to)

        for (name in after - before) {
            eventBus.publish(EntityEnteredRegion(match.id, event.playerId, name, event.atTick))
        }
        for (name in before - after) {
            eventBus.publish(EntityLeftRegion(match.id, event.playerId, name, event.atTick))
        }
    }

    private fun Stage.namedRegionsAt(point: Vec3): Set<String> =
        regions.filter { it.contains(point) }.mapNotNull { it.name }.toSet()
}
