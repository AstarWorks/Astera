package com.astarworks.astera.core.application.eventbus.listeners.portal

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.executor
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.portal.IPortalGateway
import com.astarworks.astera.core.domain.event.PlayerInteractedBlock
import com.astarworks.astera.core.domain.event.PortalActivated
import com.astarworks.astera.core.domain.world.BlockInteractAction
import org.slf4j.LoggerFactory

/**
 * On right-click [PlayerInteractedBlock], find a `RightClick`-accepting
 * portal at the clicked block and teleport the player to its target
 * (Wave 5block-embed, ADR-0120).
 *
 * Pairs with [ActivatePortalOnPlayerMovedListener] (walk-on path). Both
 * listeners can be wired simultaneously for `PortalActivationRule.Both`
 * portals; the loop guard inside each listener prevents double-fire.
 *
 * # SneakAct gating
 *
 * `PortalActivationRule.SneakAct` is treated as a sneak-only `RightClick`.
 * The current Wave 5block-embed surface does not have a per-event sneak
 * indicator (Wave 6b adapter detail), so this listener accepts SneakAct
 * the same as RightClick today. When the adapter starts publishing a
 * `sneaking: Boolean` field on `PlayerInteractedBlock`, this listener
 * flips one branch to filter; the port's `acceptsRightClick()` helper
 * already returns true for both variants so the surface change is
 * transparent.
 */
public class ActivatePortalOnPlayerInteractedBlockListener(
    private val portalGateway: IPortalGateway,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerInteractedBlock) {
        if (event.action != BlockInteractAction.RightClick) return
        val portal = portalGateway.findByPosition(event.world, event.position) ?: return
        if (!portal.activationRule.acceptsRightClick()) return
        if (event.world == portal.targetWorld && event.position == portal.targetPosition) return

        val ok = worldGateway.teleportAsync(
            playerId = event.playerId,
            at = portal.targetPosition,
            world = portal.targetWorld,
        )
        ok.thenAcceptAsync({ ok ->
        if (!ok) {
            log.debug(
                "Portal {} right-click teleport failed for {} → ({}, {})",
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
