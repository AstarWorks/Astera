package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneBoard
import com.astarworks.astera.core.application.service.lobby.LobbyLanePadService
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.lobby.LobbyLanePadLayout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Feeds player movement in the lobby world to [LobbyLanePadService] (F3-1). Only a change of
 * block triggers a lookup, so this is cheap on the move event hot path.
 */
internal class PaperLobbyLanePadListener(private val pads: LobbyLanePadService) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        val from = event.from
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        pads.onMoved(PlayerId(event.player.uniqueId), WorldId(to.world.name), Vec3(to.x, to.y, to.z))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pads.forget(PlayerId(event.player.uniqueId))
    }
}

/**
 * One text display above each lane pad showing the lane name and how many players are queued,
 * redrawn whenever [ILobbyLaneBoard] reports a change. Displays are non-persistent (rebuilt on
 * every boot) and tagged so stale ones from a crash or `/reload` are swept first. Main thread only.
 */
internal class PaperLobbyLaneHolograms(
    private val plugin: JavaPlugin,
    private val messages: IMessageRenderer,
    private val board: ILobbyLaneBoard,
) {
    private val displays = java.util.concurrent.ConcurrentHashMap<String, TextDisplay>()
    private var subscription: AutoCloseable? = null

    fun install(worldName: String, pads: Map<String, Vec3>) {
        val world = plugin.server.getWorld(worldName) ?: return
        removeAll()
        for ((lane, pad) in pads) {
            val loc = Location(world, pad.x, pad.y + HEIGHT, pad.z)
            plugin.server.regionScheduler.execute(plugin, world, loc.blockX shr 4, loc.blockZ shr 4, Runnable {
            world.getChunkAt(loc).entities.filterIsInstance<TextDisplay>().filter { TAG in it.scoreboardTags }.forEach { it.remove() }
            displays[lane] = world.spawn(loc, TextDisplay::class.java) { d ->
                com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(d, "lane-pad:$lane")
                d.addScoreboardTag(TAG)
                d.billboard = Display.Billboard.CENTER
                d.viewRange = 0.7f
                d.isSeeThrough = false
                d.isShadowed = true
                d.text(render(lane))
            }
            })
        }
        subscription = board.onChanged { lane ->
            displays[lane]?.scheduler?.execute(plugin, Runnable { refresh(lane) }, null, 1L)
        }
    }

    private fun refresh(lane: String) {
        displays[lane]?.takeIf { it.isValid }?.text(render(lane))
    }

    private fun render(lane: String): net.kyori.adventure.text.Component {
        val ph = mapOf(
            "lane" to LobbyLanePadLayout.displayName(lane),
            "count" to board.queued(lane).toString(),
        )
        val mm = MiniMessage.miniMessage()
        return mm.deserialize(messages.render(null, NAME, ph) + "\n" + messages.render(null, QUEUED, ph))
    }

    fun removeAll() {
        subscription?.close()
        subscription = null
        displays.values.forEach { entity -> entity.scheduler.execute(plugin, Runnable { entity.remove() }, null, 1L) }
        displays.clear()
    }

    private companion object {
        const val TAG = "astera_lane_hologram"
        const val HEIGHT = 3.2
        val NAME = MessageKey("astera.lobby.lane.board.name")
        val QUEUED = MessageKey("astera.lobby.lane.board.queued")
    }
}
