package com.astarworks.astera.core.adapter.api.binding

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Adapts application's [IPlayerGateway] onto the vendor-neutral [IMcServer].
 *
 * No Paper / Bukkit imports here by design — server-core specifics are
 * supplied at runtime by `adapter-minecraft-impl-paper` (or -folia, -spigot,
 * etc.) via the [IMcServer] implementation passed in.
 *
 * ADR-0022 / ADR-0073: per-item delivery belongs to the Items plugin (Wave
 * _items-1 renamed `:plugin:weapons` → `:plugin:items`). The Items plugin
 * defines its own delivery use case that resolves `ItemId` through Core's
 * non-generic `IItemRegistry` (returning `ItemSpec`) and hands it to its
 * `IItemDelivery`. Core stays item-agnostic.
 */
public class McPlayerGateway(
    private val server: IMcServer,
) : IPlayerGateway {

    override fun findByName(name: String): PlayerId? =
        server.findPlayerByName(name)?.id

    override fun nameOf(playerId: PlayerId): String? =
        server.findPlayer(playerId)?.name

    override fun sendMessage(playerId: PlayerId, rendered: String) {
        server.findPlayer(playerId)?.sendMessage(rendered)
    }
}
