package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Bukkit -> domain events the objective modes (WP11) need while a match runs:
 *
 *  - [BlockBreakEvent] -> [BlockBroken] (break-core),
 *  - [PlayerMoveEvent] -> [PlayerMoved] (region zones: tnt-spawn, wool-*, base-*, jewelry-*).
 *
 * Only players that are in a match are translated; everyone else (lobby, free
 * roam) produces no event, so the rest of the server pays nothing. Moves are
 * throttled to block changes (head turns and sub-block steps are dropped),
 * which is all the AABB zones can tell apart.
 *
 * Both handlers are MONITOR / `ignoreCancelled` so only actions that really
 * happened are observed.
 */
public class PaperMatchWorldInputAdapter(
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) : Listener {

    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBlockBreak(event: BlockBreakEvent) {
        val playerId = PlayerId(event.player.uniqueId)
        val match = matches.findMatchOf(playerId) ?: return
        val block = event.block
        val broken = BlockBroken(
            matchId = match.id,
            playerId = playerId,
            position = Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()),
            blockId = block.type.key.asString(),
            atTick = scheduler.currentTick(),
        )
        // MONITOR fires before the server removes the block. Publish a tick later so a listener that writes the block
        // back (break-core: "your own core cannot be broken") is not overwritten by the break completing afterwards.
        scheduler.runLaterTicks(ExecutionScope.Global, 1L) { eventBus.publish(broken) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlayerMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        val playerId = PlayerId(event.player.uniqueId)
        if (matches.findMatchOf(playerId) == null) return
        eventBus.publish(
            PlayerMoved(
                playerId = playerId,
                from = from.toVec3(),
                to = to.toVec3(),
                world = WorldId(to.world.name),
                atTick = scheduler.currentTick(),
            ),
        )
    }

    private fun Location.toVec3(): Vec3 = Vec3(x, y, z)
}
