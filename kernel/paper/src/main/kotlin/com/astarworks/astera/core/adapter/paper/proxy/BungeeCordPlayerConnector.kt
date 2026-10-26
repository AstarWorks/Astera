package com.astarworks.astera.core.adapter.paper.proxy

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ServerName
import org.bukkit.plugin.java.JavaPlugin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Moves an online player to another backend with the `BungeeCord` plugin
 * messaging channel's `Connect` sub-message. Velocity implements that legacy
 * channel natively, so no proxy-side plugin is needed (ADR-0179).
 */
public class BungeeCordPlayerConnector(private val plugin: JavaPlugin) {

    init {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
    }

    public fun connect(playerId: PlayerId, target: ServerName): Result<Unit, ProxyError> {
        val player = plugin.server.getPlayer(playerId.uuid) ?: return Result.Failure(ProxyError.NoProxyConnection)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeUTF("Connect")
            it.writeUTF(target.value)
        }
        player.sendPluginMessage(plugin, CHANNEL, out.toByteArray())
        return Result.Success(Unit)
    }

    public fun close() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
    }

    private companion object {
        const val CHANNEL = "BungeeCord"
    }
}
