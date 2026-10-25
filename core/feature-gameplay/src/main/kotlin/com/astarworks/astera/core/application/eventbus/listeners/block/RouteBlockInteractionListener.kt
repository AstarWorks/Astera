package com.astarworks.astera.core.application.eventbus.listeners.block

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.block.BlockInteractionAction
import com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter
import com.astarworks.astera.core.domain.event.PlayerInteractedBlock
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.item.ItemId
import org.slf4j.LoggerFactory

/**
 * On [PlayerInteractedBlock], resolve the bound
 * [BlockInteractionAction] for the `(world, position, action)` triple
 * and dispatch it (Wave 5block-embed, ADR-0120).
 *
 * # Why
 *
 * The Wave 5world-events audit found `PlayerInteractedBlock` had **zero
 * subscribers**: Bukkit publishes the event but nothing reacts. This
 * listener is the canonical subscriber that bridges the event to the
 * block-binding routing table ([IBlockInteractionRouter], Wave 5block-embed).
 *
 * # Dispatch matrix
 *
 * | Variant                       | Sink                                              |
 * |-------------------------------|---------------------------------------------------|
 * | [BlockInteractionAction.RunCommand]   | TODO (Wave 6 — needs command-context bridge)      |
 * | [BlockInteractionAction.OpenGui]      | [IGuiAdapter.open] for the interacting player     |
 * | [BlockInteractionAction.TriggerSkill] | [SkillCastRequested] published on the event bus   |
 * | [BlockInteractionAction.TeleportTo]   | [IWorldGateway.teleport]                          |
 * | [BlockInteractionAction.Custom]       | TODO (Wave 6 — needs intent dispatcher injection) |
 *
 * The Wave-6-pending sinks log INFO + drop the dispatch so the binding
 * registry stays exercised in tests without dragging in adapter machinery
 * that doesn't exist yet. When the bridges land, the placeholders flip
 * to real calls in the same listener (no API change).
 *
 * # Player resolution
 *
 * GUI open and teleport are per-player operations; [IMcServer.findPlayer]
 * is used to resolve the interacting player. Skill cast is published as
 * an event (no player handle needed). The Wave-6-pending sinks above
 * don't yet resolve the player.
 */
public class RouteBlockInteractionListener(
    private val router: IBlockInteractionRouter,
    private val guiAdapter: IGuiAdapter,
    private val worldGateway: IWorldGateway,
    private val mcServer: IMcServer,
    private val eventBus: IDomainEventBus,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerInteractedBlock) {
        val action = router.lookup(event.world, event.position, event.action) ?: return

        when (action) {
            is BlockInteractionAction.RunCommand -> {
                // Wave 6 bridge: IAsteraCommandRegistry does not yet expose
                // "execute spec as player by id" — needs a CommandContext
                // synthesised from the player handle. Tracked under the
                // block-embed follow-up ADR.
                log.info(
                    "Block interaction RunCommand dispatch is Wave 6 (player={}, pos={}, world={}).",
                    event.playerId,
                    event.position,
                    event.world,
                )
            }

            is BlockInteractionAction.OpenGui -> {
                val player = mcServer.findPlayer(event.playerId)
                if (player == null) {
                    log.debug(
                        "Block interaction OpenGui: player {} not online, skipping.",
                        event.playerId,
                    )
                    return
                }
                val opened = guiAdapter.open(player, action.gui)
                if (!opened) {
                    log.warn(
                        "Block interaction OpenGui: open returned false for player {} (gui={})",
                        event.playerId,
                        action.gui.id,
                    )
                }
            }

            is BlockInteractionAction.TriggerSkill -> {
                eventBus.publish(
                    SkillCastRequested(
                        actorId = event.playerId,
                        skillId = action.skillId,
                        origin = event.position,
                        sourceItemId = BLOCK_INTERACTION_SOURCE_ITEM,
                        cooldownOverrideTicks = null,
                    ),
                )
            }

            is BlockInteractionAction.TeleportTo -> {
                val ok = worldGateway.teleport(event.playerId, action.position, action.world)
                if (!ok) {
                    log.debug(
                        "Block interaction TeleportTo failed for {} → {} ({})",
                        event.playerId,
                        action.position,
                        action.world,
                    )
                }
            }

            is BlockInteractionAction.Custom -> {
                // Wave 6 bridge: a dedicated `IBlockIntentDispatcher` will
                // route custom intents the same way `IGuiIntentRouter`
                // routes GUI intents. Until that lands, log + drop.
                log.info(
                    "Block interaction Custom intent '{}' dispatch is Wave 6 (player={}).",
                    action.intentKey,
                    event.playerId,
                )
            }
        }
    }

    public companion object {
        /**
         * `sourceItemId` carried on [SkillCastRequested] when the cast is
         * triggered by a block binding (not by a held item). Combat
         * subscribers may key off this id to route the cast through a
         * non-item-cost path.
         */
        public val BLOCK_INTERACTION_SOURCE_ITEM: ItemId = ItemId("astera-block-interaction")
    }
}
