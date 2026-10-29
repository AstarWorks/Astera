package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.lobby.MatchCompassTag
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.lobby.HotbarLayout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IMatchCompass
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper [IMatchCompass]: a vanilla COMPASS tagged [MatchCompassTag] in hotbar slot index 8. Every path that could move
 * it (click, creative click, drag, number-key / offhand swap, drop, hand swap, death drop) is cancelled; death and
 * quit strip it so neither a drop nor the saved playerdata keeps it. A respawn gives it back through [give].
 */
public class PaperMatchCompass(
    private val plugin: JavaPlugin,
    private val messages: IMessageRenderer,
) : IMatchCompass, Listener {

    public fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun give(player: PlayerId) {
        val online = plugin.server.getPlayer(player.uuid) ?: return
        if (!online.isOnline) return
        online.scheduler.execute(plugin, Runnable { place(online) }, null, 1L)
    }

    override fun remove(player: PlayerId) {
        val online = plugin.server.getPlayer(player.uuid) ?: return
        online.scheduler.execute(plugin, Runnable { strip(online) }, null, 1L)
    }

    override fun isHeld(player: PlayerId): Boolean {
        val online = plugin.server.getPlayer(player.uuid) ?: return false
        return MatchCompassTag.isCompass(online.inventory.itemInMainHand)
    }

    private fun place(player: Player) {
        if (!player.isOnline) return
        val inv = player.inventory
        if (MatchCompassTag.isCompass(inv.getItem(SLOT))) return
        // A stray compass elsewhere (e.g. dragged in before the lock) goes; the one true copy sits in the slot.
        strip(player)
        org.slf4j.LoggerFactory.getLogger(PaperMatchCompass::class.java).info("Inventory write for {} (source=match-compass, slot={})", player.name, SLOT)
        inv.getItem(SLOT)?.takeIf { !it.isEmpty }?.let { displaced ->
            inv.setItem(SLOT, null)
            inv.addItem(displaced).values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        inv.setItem(SLOT, build(player))
    }

    private fun strip(player: Player) {
        val inv = player.inventory
        for (i in 0 until inv.size) if (MatchCompassTag.isCompass(inv.getItem(i))) inv.setItem(i, null)
        if (MatchCompassTag.isCompass(player.itemOnCursor)) player.setItemOnCursor(null)
    }

    private fun build(player: Player): ItemStack {
        val id = PlayerId(player.uniqueId)
        val stack = ItemStack(Material.COMPASS)
        stack.editMeta { meta ->
            meta.displayName(AsteraTextDeserializer.deserialize(messages.render(id, NAME)).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(AsteraTextDeserializer.deserialize(messages.render(id, LORE)).decoration(TextDecoration.ITALIC, false)))
            meta.setMaxStackSize(1)
        }
        MatchCompassTag.mark(stack)
        return stack
    }

    // ---- locks ---------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(e: InventoryClickEvent) = handleClick(e)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCreativeClick(e: InventoryCreativeEvent) = handleClick(e)

    private fun handleClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val swapItem = when (e.click) {
            ClickType.NUMBER_KEY -> if (e.hotbarButton in 0..8) player.inventory.getItem(e.hotbarButton) else null
            ClickType.SWAP_OFFHAND -> player.inventory.itemInOffHand
            else -> null
        }
        if (MatchCompassTag.isCompass(e.currentItem) || MatchCompassTag.isCompass(e.cursor) || MatchCompassTag.isCompass(swapItem)) {
            e.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(e: InventoryDragEvent) {
        if (MatchCompassTag.isCompass(e.oldCursor) || MatchCompassTag.isCompass(e.cursor) ||
            e.rawSlots.any { MatchCompassTag.isCompass(e.view.getItem(it)) }
        ) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(e: PlayerDropItemEvent) {
        if (MatchCompassTag.isCompass(e.itemDrop.itemStack)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwapHands(e: PlayerSwapHandItemsEvent) {
        if (MatchCompassTag.isCompass(e.mainHandItem) || MatchCompassTag.isCompass(e.offHandItem)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(e: PlayerDeathEvent) {
        e.drops.removeIf { MatchCompassTag.isCompass(it) }
        e.itemsToKeep.removeIf { MatchCompassTag.isCompass(it) }
        strip(e.player)
    }

    /** LOWEST: before the snapshot listener's save, so playerdata never keeps the compass. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(e: PlayerQuitEvent) = strip(e.player)

    private companion object {
        const val SLOT = HotbarLayout.MATCH_COMPASS_SLOT
        val NAME = MessageKey("astera.match.compass.name")
        val LORE = MessageKey("astera.match.compass.lore")
    }
}
