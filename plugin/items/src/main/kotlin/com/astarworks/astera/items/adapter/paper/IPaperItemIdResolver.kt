package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Adapter port: read the `astera:item_id` PDC tag off the player's main-hand
 * stack (Wave _items-1, ADR-0073).
 *
 * Lives in `adapter/paper/` rather than `application/port/outbound/` because
 * Bukkit's `PersistentDataContainer` is the only mechanism that lets one
 * vanilla material back many distinct `ItemSpec`s — there's no vendor-neutral
 * abstraction worth introducing for this single PDC read. Konsist allows
 * Bukkit imports under `adapter/paper/`.
 *
 * Returns `null` when the hand is empty, the stack has no `astera:item_id` PDC
 * key, or the stored string is not a valid [ItemId]. Callers (the
 * `ItemSkillTriggerListener`) treat all three indistinguishably as "no Astera
 * item in hand".
 */
public interface IPaperItemIdResolver {
    public fun heldItemId(playerId: PlayerId): ItemId?

    /** Item ids of every Astera stack in the player's inventory (hotbar, main, armor, off-hand), in slot order. */
    public fun inventoryItemIds(playerId: PlayerId): List<ItemId>

    /** Removes one [itemId] stack unit from the first matching inventory slot; `false` when none is carried. */
    public fun removeOne(playerId: PlayerId, itemId: ItemId): Boolean
}
