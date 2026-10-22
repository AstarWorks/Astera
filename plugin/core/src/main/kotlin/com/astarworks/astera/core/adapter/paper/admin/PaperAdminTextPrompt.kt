package com.astarworks.astera.core.adapter.paper.admin

import com.astarworks.astera.core.application.port.outbound.admin.IAdminTextPrompt
import com.astarworks.astera.core.domain.player.PlayerId
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat-backed [IAdminTextPrompt] (ADR-0233): the next chat line of a player with a pending prompt is
 * swallowed (never broadcast) and handed to the callback on the main thread. Quitting drops the
 * prompt.
 */
public class PaperAdminTextPrompt(private val plugin: Plugin) : IAdminTextPrompt, Listener {

    private val pending: MutableMap<PlayerId, (String?) -> Unit> = ConcurrentHashMap()

    override fun ask(player: PlayerId, onSubmit: (String?) -> Unit) {
        pending[player] = onSubmit
    }

    override fun isWaiting(player: PlayerId): Boolean = pending.containsKey(player)

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onChat(event: AsyncChatEvent) {
        val id = PlayerId(event.player.uniqueId)
        val callback = pending.remove(id) ?: return
        event.isCancelled = true
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        plugin.server.scheduler.runTask(plugin, Runnable { callback(text) })
    }

    @EventHandler
    public fun onQuit(event: PlayerQuitEvent) {
        pending.remove(PlayerId(event.player.uniqueId))
    }
}
