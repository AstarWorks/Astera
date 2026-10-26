package com.astarworks.astera.core.adapter.paper.admin

import com.astarworks.astera.core.application.port.outbound.admin.AdminGameMode
import com.astarworks.astera.core.application.port.outbound.admin.AdminOnlinePlayer
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.entity.Player

/**
 * Paper implementation of [IAdminPlayerGateway] (F1-D, ADR-0183). Mutators run on the target player's Entity scope.
 */
public class PaperAdminPlayerGateway(private val server: Server, private val plugin: org.bukkit.plugin.Plugin) : IAdminPlayerGateway {

    override fun isOperator(player: PlayerId): Boolean = find(player)?.isOp == true

    override fun onlinePlayers(): List<AdminOnlinePlayer> =
        server.onlinePlayers
            .map { AdminOnlinePlayer(PlayerId(it.uniqueId), it.name) }
            .sortedBy { it.name.lowercase() }

    override fun teleportToPlayer(who: PlayerId, destination: PlayerId): java.util.concurrent.CompletableFuture<Boolean> {
        val completed = java.util.concurrent.CompletableFuture<Boolean>()
        val a = find(who) ?: return completed.also { it.complete(false) }
        val b = find(destination) ?: return completed.also { it.complete(false) }
        val accepted = b.scheduler.execute(plugin, Runnable {
            val target = b.location.clone()
            if (!a.scheduler.execute(plugin, Runnable {
                a.teleportAsync(target).whenComplete { moved, failure ->
                    completed.complete(failure == null && moved == true)
                }
            }, { completed.complete(false) }, 1L)) completed.complete(false)
        }, { completed.complete(false) }, 1L)
        if (!accepted) completed.complete(false)
        return completed
    }

    override fun toggleGameMode(player: PlayerId): AdminGameMode? {
        val p = find(player) ?: return null
        val next = if (p.gameMode == GameMode.CREATIVE) GameMode.SURVIVAL else GameMode.CREATIVE
        p.gameMode = next
        return if (next == GameMode.CREATIVE) AdminGameMode.CREATIVE else AdminGameMode.SURVIVAL
    }

    override fun toggleFlight(player: PlayerId): Boolean? {
        val p = find(player) ?: return null
        val enable = !p.allowFlight
        p.allowFlight = enable
        p.isFlying = enable && !p.isOnGround
        return enable
    }

    override fun kick(player: PlayerId, reason: String): Boolean {
        val p = find(player) ?: return false
        p.kick(Component.text(reason))
        return true
    }

    private fun find(id: PlayerId): Player? = server.getPlayer(id.uuid)
}
