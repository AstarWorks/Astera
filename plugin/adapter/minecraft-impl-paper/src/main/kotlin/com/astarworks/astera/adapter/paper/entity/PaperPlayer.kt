package com.astarworks.astera.adapter.paper.entity

import com.astarworks.astera.adapter.minecraftapi.entity.IMcPlayer
import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.player.PlayerId
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paper concrete of [IMcPlayer]. The only layer where `org.bukkit.*` imports
 * are sanctioned for player operations (see ADR-0002 and the Konsist guard
 * in :tools:architecture-test).
 *
 * Phase 1 uses Bukkit's vanilla `Material` for [giveMaterial]; the Oraxen
 * provider module will intercept non-vanilla `materialKey` values before
 * the call ever reaches this class (Phase 2+).
 */
class PaperPlayer(private val bukkit: Player) : IMcPlayer {

    override val id: PlayerId = PlayerId(bukkit.uniqueId)
    override val name: String = bukkit.name
    override val location: Vec3 get() = bukkit.location.let { Vec3(it.x, it.y, it.z) }
    override val locale: String? get() = bukkit.locale().toString()

    override fun sendMessage(rendered: String) {
        bukkit.sendMessage(MM.deserialize(rendered))
    }

    override fun giveMaterial(materialKey: String, displayName: String?, lore: List<String>) {
        val material = Material.matchMaterial(materialKey) ?: Material.STONE
        val item = ItemStack(material)
        item.editMeta { meta ->
            if (displayName != null) meta.displayName(MM.deserialize(displayName))
            if (lore.isNotEmpty()) meta.lore(lore.map { MM.deserialize(it) })
        }
        bukkit.inventory.addItem(item)
    }

    private companion object {
        val MM: MiniMessage = MiniMessage.miniMessage()
    }
}
