package com.astarworks.astera.adapter.minecraftapi.binding

import com.astarworks.astera.adapter.minecraftapi.server.IMcServer
import com.astarworks.astera.application.port.outbound.IMessageRenderer
import com.astarworks.astera.application.port.outbound.IPlayerGateway
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponSpec

/**
 * Adapts application's [IPlayerGateway] onto the vendor-neutral [IMcServer].
 *
 * No Paper / Bukkit imports here by design — server-core specifics are
 * supplied at runtime by `adapter-minecraft-impl-paper` (or -folia, -spigot,
 * etc.) via the [IMcServer] implementation passed in.
 */
class McPlayerGateway(
    private val server: IMcServer,
    private val messages: IMessageRenderer,
) : IPlayerGateway {

    override fun findByName(name: String): PlayerId? =
        server.findPlayerByName(name)?.id

    override fun sendMessage(playerId: PlayerId, rendered: String) {
        server.findPlayer(playerId)?.sendMessage(rendered)
    }

    override fun giveWeapon(playerId: PlayerId, weapon: WeaponSpec) {
        val mc = server.findPlayer(playerId) ?: return
        val displayName = messages.render(playerId, weapon.displayNameKey)
        val lore = messages.renderLore(playerId, weapon.loreKey)
        mc.giveMaterial(weapon.materialKey, displayName, lore)
    }
}
