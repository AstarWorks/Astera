package com.astarworks.astera.core.application.eventbus.listeners.portal

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.executor
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.portal.IPortalGateway
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.event.PortalActivated
import org.slf4j.LoggerFactory

/**
 * On [PlayerMoved], find a `WalkOn`-accepting portal at the destination
 * and teleport the player to its target (Wave 5block-embed, ADR-0120).
 *
 * # Why subscribe `PlayerMoved`
 *
 * Wave 5world-events publishes `PlayerMoved` at the throttled cadence
 * the adapter chooses (≤ 1/0.5 block per ADR-0111). Subscribing here
 * gives portals a uniform trigger surface alongside the auto-pickup
 * listener (`AutoPickupOnPlayerMovedListener`, Wave 5complete).
 *
 * # Behaviour
 *
 *  1. Look up a portal at the new position via [IPortalGateway.findByPosition].
 *  2. Bail if no portal or the portal's `activationRule.acceptsWalkOn()`
 *     is false.
 *  3. Bail if the new position equals the portal's `targetPosition` in
 *     the portal's `targetWorld` — guards against an infinite teleport
 *     loop when the target falls inside the source region.
 *  4. Call [IWorldGateway.teleport]; if it returns false (player offline,
 *     unknown world), log DEBUG and stop.
 *  5. On success publish [PortalActivated] for downstream telemetry /
 *     achievement / replay subscribers.
 */
public class ActivatePortalOnPlayerMovedListener(
    private val portalGateway: IPortalGateway,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerMoved) {
        val portal = portalGateway.findByPosition(event.world, event.to) ?: return
        if (!portal.activationRule.acceptsWalkOn()) return
        // Loop guard: don't fire if the player is already at the target.
        if (event.world == portal.targetWorld && event.to == portal.targetPosition) return

        val ok = worldGateway.teleportAsync(
            playerId = event.playerId,
            at = portal.targetPosition,
            world = portal.targetWorld,
        )
        ok.thenAcceptAsync({ ok ->
        if (!ok) {
            log.debug(
                "Portal {} walk-on teleport failed for {} → ({}, {})",
                portal.id,
                event.playerId,
                portal.targetWorld,
                portal.targetPosition,
            )
            return@thenAcceptAsync
        }
        eventBus.publish(
            PortalActivated(
                playerId = event.playerId,
                portalId = portal.id,
                atTick = event.atTick,
            ),
        )
        }, scheduler.executor(ExecutionScope.Entity(event.playerId.uuid)))
    }
}
