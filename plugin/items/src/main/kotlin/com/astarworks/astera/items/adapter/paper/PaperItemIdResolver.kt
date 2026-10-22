package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.paper.lobby.TrainingItemTag
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Paper-side concrete of [IPaperItemIdResolver] (Wave _items-1, ADR-0073).
 *
 * Reads the `astera:item_id` PDC string off the player's main-hand `ItemStack`
 * by way of `ItemMeta.getPersistentDataContainer().get(KEY, STRING)`. The key
 * is built once at construction so the resolver doesn't allocate a fresh
 * `NamespacedKey` per click.
 *
 * Safe defaults: returns `null` for offline players, empty hand, item without
 * PDC, item without the `astera:item_id` key, or a stored string that fails
 * [ItemId] validation. Konsist allows Bukkit imports here (`adapter/paper`).
 */
public class PaperItemIdResolver(
    plugin: Plugin,
    private val server: Server,
) : IPaperItemIdResolver {

    private val itemIdKey: NamespacedKey = NamespacedKey(plugin, ITEM_ID_KEY)

    override fun heldItemId(playerId: PlayerId): ItemId? {
        val player = server.getPlayer(playerId.uuid) ?: return null
        return itemIdOf(player.inventory.itemInMainHand)
    }

    override fun inventoryItemIds(playerId: PlayerId): List<ItemId> {
        val player = server.getPlayer(playerId.uuid) ?: return emptyList()
        val inv = player.inventory
        return (inv.contents.asList() + inv.armorContents.asList() + inv.itemInOffHand)
            .filterNot(TrainingItemTag::isTraining) // lobby rack stacks are lent, not owned
            .mapNotNull(::itemIdOf)
    }

    /** The `astera:item_id` of [stack], or `null` for air / non-Astera stacks. */
    public fun itemIdOf(stack: ItemStack?): ItemId? {
        val meta = stack?.itemMeta ?: return null
        val raw = meta.persistentDataContainer.get(itemIdKey, PersistentDataType.STRING) ?: return null
        return runCatching { ItemId(raw) }.getOrNull()
    }

    public companion object {
        public const val ITEM_ID_KEY: String = "item_id"
    }
}
