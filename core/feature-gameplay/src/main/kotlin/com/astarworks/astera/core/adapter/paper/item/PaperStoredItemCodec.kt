package com.astarworks.astera.core.adapter.paper.item

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.StoredItemState
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.slf4j.LoggerFactory

/**
 * ItemStack <-> id-based storage form (ADR-0302), shared by the inventory snapshot and the personal vault.
 *
 * Storing: an Astera stack (it carries the `item_id` PDC string) becomes `{itemId, count, state}`; any other
 * stack becomes its vanilla material key. Nothing else of the stack is kept - name, lore, tooltip style,
 * models, attributes and PDC are all derived data.
 *
 * Loading: an Astera id is rebuilt through [PaperItemMaterializer.buildFor] from the **current** definition in
 * [IItemRegistry]; a vanilla key becomes a plain `ItemStack`. An id whose definition was deleted, or an
 * unknown material, is dropped with a WARN. The per-instance [StoredItemState] (durability damage and vanilla
 * enchantments) is applied on top.
 *
 * The item-id key is matched by its path only (`item_id`), ignoring the plugin namespace the materializer
 * happened to be created under.
 */
public class PaperStoredItemCodec(
    private val materializer: () -> PaperItemMaterializer?,
    private val items: () -> IItemRegistry?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The id-based form of one stack. [vanilla] is true when [itemId] is a material key, not an Astera item id. */
    public data class Stored(val itemId: String, val vanilla: Boolean, val count: Int, val state: StoredItemState)

    /** The Astera item id carried by [stack], or `null` for air and non-Astera stacks. */
    public fun asteraIdOf(stack: ItemStack?): String? {
        val pdc = stack?.takeUnless { it.isEmpty }?.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == PaperItemMaterializer.ITEM_ID_KEY } ?: return null
        return pdc.get(key, PersistentDataType.STRING)
    }

    public fun encode(stack: ItemStack): Stored? {
        if (stack.isEmpty) return null
        val asteraId = asteraIdOf(stack)
        val count = stack.amount.coerceIn(1, MAX_COUNT)
        val damage = (stack.itemMeta as? Damageable)?.damage?.coerceAtLeast(0) ?: 0
        val enchants = stack.enchantments.entries
            .map { (enchantment, level) -> enchantment.key.toString() to level }
            // The materializer re-adds its hidden glint enchantment from the definition.
            .filterNot { (key, level) -> asteraId != null && key == GLINT_ENCHANT && level == 1 }
            .toMap()
        val state = StoredItemState(damage, enchants)
        return if (asteraId != null) {
            Stored(asteraId, vanilla = false, count = count, state = state)
        } else {
            Stored(stack.type.key.toString(), vanilla = true, count = count, state = state)
        }
    }

    /** Rebuilds the stack for [stored], rendered for [viewer]'s locale; `null` (after a WARN) when it cannot be rebuilt. */
    public fun decode(stored: Stored, viewer: PlayerId?, where: String): ItemStack? {
        val stack = if (stored.vanilla) decodeVanilla(stored, where) else decodeAstera(stored, viewer, where)
        return stack?.also { applyState(it, stored.state) }
    }

    private fun decodeAstera(stored: Stored, viewer: PlayerId?, where: String): ItemStack? {
        val id = runCatching { ItemId(stored.itemId) }.getOrNull()
        val spec = id?.let { items()?.find(it) }
        if (spec == null) {
            log.warn("{}: item id '{}' (x{}) has no current definition; dropped", where, stored.itemId, stored.count)
            return null
        }
        val materializer = materializer()
        if (materializer == null) {
            log.warn("{}: item materializer not available; item '{}' (x{}) dropped", where, stored.itemId, stored.count)
            return null
        }
        return materializer.buildFor(spec, viewer, stored.count)
    }

    private fun decodeVanilla(stored: Stored, where: String): ItemStack? {
        val material = Material.matchMaterial(stored.itemId)?.takeUnless { it.isAir }
        if (material == null) {
            log.warn("{}: unknown material '{}' (x{}); dropped", where, stored.itemId, stored.count)
            return null
        }
        return ItemStack(material, stored.count.coerceAtMost(material.maxStackSize))
    }

    private fun applyState(stack: ItemStack, state: StoredItemState) {
        if (state.damage > 0) stack.editMeta(Damageable::class.java) { it.damage = state.damage }
        for ((rawKey, level) in state.enchants) {
            val enchantment = NamespacedKey.fromString(rawKey)?.let { Registry.ENCHANTMENT.get(it) }
            if (enchantment == null) {
                log.warn("Unknown enchantment '{}' on a stored item; dropped", rawKey)
            } else {
                stack.addUnsafeEnchantment(enchantment, level)
            }
        }
    }

    private companion object {
        const val MAX_COUNT = 64
        const val GLINT_ENCHANT = "minecraft:luck_of_the_sea"
    }
}
