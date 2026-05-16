package com.astarworks.astera.adapter.minecraftapi.binding

import com.astarworks.astera.adapter.minecraftapi.item.IMcItemTemplate
import com.astarworks.astera.adapter.minecraftapi.server.IMcServer
import com.astarworks.astera.application.port.outbound.IItemRegistry
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
 *
 * Stage 1 D integration: `giveWeapon` resolves `weapon.itemId` through
 * [items], so the same `ItemId` flows from `content/weapons/<id>.yaml` →
 * `WeaponSpec` → `IItemRegistry` → `IMcItemTemplate` → `IMcPlayer.giveItem`.
 *
 * A weapon whose itemId is unregistered is silently skipped at give-time
 * (the `MutableItemRegistry` load step logs the missing template; we don't
 * double-log here because `adapter-minecraft-api` deliberately has no slf4j
 * dependency).
 */
class McPlayerGateway(
    private val server: IMcServer,
    private val messages: IMessageRenderer,
    private val items: IItemRegistry<IMcItemTemplate>,
) : IPlayerGateway {

    override fun findByName(name: String): PlayerId? =
        server.findPlayerByName(name)?.id

    override fun sendMessage(playerId: PlayerId, rendered: String) {
        server.findPlayer(playerId)?.sendMessage(rendered)
    }

    override fun giveWeapon(playerId: PlayerId, weapon: WeaponSpec) {
        val mc = server.findPlayer(playerId) ?: return
        val template = items.resolveOrNull(weapon.itemId) ?: return
        val displayName = messages.render(playerId, weapon.displayNameKey)
        val lore = messages.renderLore(playerId, weapon.loreKey)
        mc.giveItem(template, displayName, lore)
    }
}
