package com.astarworks.astera.chat.adapter.paper

import com.astarworks.astera.chat.application.ChatRelay
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

public class PaperChatListener(
    private val plugin: JavaPlugin,
    private val relay: ChatRelay,
    private val permissions: IPermissionService,
    private val appearance: (PlayerId) -> Pair<String, String>,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onChat(event: AsyncChatEvent) {
        event.isCancelled = true
        val player = event.player
        val message = PlainTextComponentSerializer.plainText().serialize(event.message()).take(256)
        // Permission and role lookups may touch the player's host handle; resolve on its owning region.
        player.scheduler.run(plugin, {
            val id = PlayerId(player.uniqueId)
            val rank = relay.rank(permissions.rolesOf(id).map { it.value }.toSet())
            val color = permissions.check(id, Permission("astera.chat.color")) || player.hasPermission("astera.chat.color")
            val name = player.name
            val (cosmeticTitle, nameColor) = appearance(id)
            plugin.server.asyncScheduler.runNow(plugin) {
                relay.send(player.uniqueId, name, message, rank.copy(prefix = rank.prefix + cosmeticTitle), color, nameColor).exceptionally {
                    plugin.logger.warning("Chat delivery failed (${it.javaClass.simpleName})")
                    null
                }
            }
        }, null)
    }

    @EventHandler
    public fun onQuit(event: PlayerQuitEvent) { relay.forget(event.player.uniqueId) }
}
