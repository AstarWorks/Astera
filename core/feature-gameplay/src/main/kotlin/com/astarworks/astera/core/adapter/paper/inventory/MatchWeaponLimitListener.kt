package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.application.service.inventory.WeaponLimitService
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/**
 * Match weapon restriction at the inventory edge: refuses a weapon that would push the player past
 * the mode's `weapon_limits` (see [WeaponLimitService]). The verdict is `Allowed` outside a match,
 * so lobby inventories are untouched.
 *
 * Covered ways a weapon enters the inventory during a match:
 *  - ground pickup ([EntityPickupItemEvent]);
 *  - shift-click / hotbar-key move out of another inventory (chest, GUI-less container);
 *  - placing or swapping the cursor stack into the player's own slots, and dragging it over them.
 *
 * A cursor stack that came from the player's own inventory is not counted by the owned-item query
 * (it left the slot), so re-arranging what you already carry is never blocked.
 */
public class MatchWeaponLimitListener(
    plugin: JavaPlugin,
    private val service: () -> WeaponLimitService?,
) : Listener {

    private val itemIdKey: NamespacedKey = NamespacedKey(plugin, PaperItemMaterializer.ITEM_ID_KEY)
    private val lastNotice: ConcurrentHashMap<PlayerId, Long> = ConcurrentHashMap()

    public fun start(plugin: JavaPlugin, listeners: OwnedListeners) {
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (refuses(player, event.item.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val clicked = event.clickedInventory ?: return
        val intoPlayer = clicked == player.inventory
        val blocked = when {
            // Pulling a stack out of another inventory into ours.
            !intoPlayer && (event.isShiftClick || event.click == ClickType.NUMBER_KEY) -> refuses(player, event.currentItem)
            // Dropping the cursor stack into one of our own slots.
            intoPlayer && event.action in CURSOR_INTO_SLOT -> {
                val cursor = event.cursor
                // A one-for-one swap of the same family keeps the counts: always fine.
                if (event.action == InventoryAction.SWAP_WITH_CURSOR && sameFamily(cursor, event.currentItem)) false
                else refuses(player, cursor)
            }
            else -> false
        }
        if (blocked) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val topSize = event.view.topInventory.size
        if (event.rawSlots.none { it >= topSize }) return
        if (refuses(player, event.oldCursor)) event.isCancelled = true
    }

    /** `true` (and the player is told) when adding [stack] would break the match weapon cap. */
    private fun refuses(player: Player, stack: ItemStack?): Boolean {
        val itemId = itemIdOf(stack) ?: return false
        val service = service() ?: return false
        val playerId = PlayerId(player.uniqueId)
        val verdict = service.verdictFor(playerId, itemId) as? WeaponLimitService.Verdict.Blocked ?: return false
        val now = System.currentTimeMillis()
        val previous = lastNotice[playerId]
        if (previous == null || now - previous >= NOTICE_COOLDOWN_MS) {
            lastNotice[playerId] = now
            service.notifyBlocked(playerId, verdict.blocker)
        }
        return true
    }

    private fun sameFamily(a: ItemStack?, b: ItemStack?): Boolean {
        val service = service() ?: return false
        val idA = itemIdOf(a) ?: return false
        val idB = itemIdOf(b) ?: return false
        return service.sameCategory(idA, idB)
    }

    private fun itemIdOf(stack: ItemStack?): ItemId? {
        if (stack == null || stack.type.isAir) return null
        val raw = stack.itemMeta?.persistentDataContainer?.get(itemIdKey, PersistentDataType.STRING) ?: return null
        return runCatching { ItemId(raw) }.getOrNull()
    }

    private companion object {
        const val NOTICE_COOLDOWN_MS: Long = 1500
        val CURSOR_INTO_SLOT: Set<InventoryAction> = setOf(
            InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_ONE,
            InventoryAction.PLACE_SOME,
            InventoryAction.SWAP_WITH_CURSOR,
        )
    }
}
