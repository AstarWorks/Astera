package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.item.ItemId

/**
 * Predicate that decides whether [itemId] is allowed in canonical slot
 * [slotIndex] (Wave 5world-events, ADR-0111).
 *
 * The default Core implementation ([DefaultSlotTypeLockRule]) enforces the
 * Minecraft-native locks:
 *
 *  - slot 36 (boots) — only items whose id ends with `-boots` / `_boots` /
 *    contains `boots`
 *  - slot 37 (leggings) — `leggings`
 *  - slot 38 (chestplate) — `chestplate`
 *  - slot 39 (helmet) — `helmet`
 *  - slot 40 (off-hand) — no restriction (Minecraft allows any stackable)
 *  - slot 0..35 — no restriction
 *
 * Item-side schema overrides (Wave 5refine refinement layer, Wave _items-1
 * `ItemSpec.equipSlots`) replace this default when the registry knows about
 * the item. Until then the keyword-matching default keeps the slot semantics
 * predictable for tests + manual GUIs.
 *
 * Domain-side because the rule itself is pure (no I/O, no Bukkit). Wiring
 * to an `IItemRegistry`-aware override lives in the application layer
 * (`SlotTypeLockResolver`).
 */
public fun interface SlotTypeLockRule {
    public fun allows(slotIndex: Int, itemId: ItemId): Boolean

    public companion object {
        /** Permissive default — every slot accepts every item. */
        public val PERMISSIVE: SlotTypeLockRule = SlotTypeLockRule { _, _ -> true }
    }
}

/**
 * Minecraft-native default rule for armor slots.
 *
 * Looks for the canonical token (`helmet` / `chestplate` / `leggings` /
 * `boots`) inside the item id. Custom items that follow Astera's
 * lower-kebab-case convention (`obsidian-helmet`, `phoenix-leggings`) pass
 * naturally; truly arbitrary ids (`shield-of-mending`) are rejected from
 * armor slots — which matches the Minecraft behaviour of "can't put a
 * shield in the helmet slot".
 *
 * Off-hand (slot 40) and main / hotbar (0..35) accept anything.
 */
public object DefaultSlotTypeLockRule : SlotTypeLockRule {
    private const val SLOT_BOOTS = 36
    private const val SLOT_LEGGINGS = 37
    private const val SLOT_CHESTPLATE = 38
    private const val SLOT_HELMET = 39

    private val armorKeywordBySlot = mapOf(
        SLOT_BOOTS to "boots",
        SLOT_LEGGINGS to "leggings",
        SLOT_CHESTPLATE to "chestplate",
        SLOT_HELMET to "helmet",
    )

    override fun allows(slotIndex: Int, itemId: ItemId): Boolean {
        val keyword = armorKeywordBySlot[slotIndex] ?: return slotIndex in 0..40
        return itemId.value.contains(keyword)
    }
}
