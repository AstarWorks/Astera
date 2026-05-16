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
 * ADR-0022 trim: Phase 2 removes `giveWeapon(WeaponSpec)` from this binding
 * because `WeaponSpec` belongs to `:plugin:weapons`. The Weapons plugin
 * defines its own `IWeaponDelivery` outbound port plus a Paper adapter that
 * resolves `ItemId` through `IItemRegistry<IMcItemTemplate>` and calls
 * `IMcPlayer.giveItem`. Core stays weapon-agnostic.
 */
public class McPlayerGateway(
    private val server: IMcServer,
) : IPlayerGateway {

    override fun findByName(name: String): PlayerId? =
        server.findPlayerByName(name)?.id

    override fun sendMessage(playerId: PlayerId, rendered: String) {
        server.findPlayer(playerId)?.sendMessage(rendered)
    }
}
