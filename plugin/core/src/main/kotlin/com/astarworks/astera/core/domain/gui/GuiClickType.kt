package com.astarworks.astera.core.domain.gui

/**
 * Vendor-neutral subset of Bukkit `org.bukkit.event.inventory.ClickType`
 * (Wave 5h-fix2, ADR-0074 §2 拡張).
 *
 * 5 variants — gameplay-relevant click distinctions:
 *
 *  - [LEFT]        — standard click (primary action: buy, confirm)
 *  - [RIGHT]       — secondary action (preview, info, alt-buy)
 *  - [SHIFT_LEFT]  — shift + left-click (bulk action, quick-buy)
 *  - [SHIFT_RIGHT] — shift + right-click (alt bulk, mark favorite)
 *  - [MIDDLE]      — middle-click (creative-pick semantics, rarely used)
 *
 * Bukkit's full `ClickType` enum has 10+ variants including DROP /
 * NUMBER_KEY / DOUBLE_CLICK / SWAP_OFFHAND — those are
 * inventory-manipulation events that the Paper GUI adapter unconditionally
 * cancels (Astera GUI is a read-only surface, no item drag/swap allowed).
 * Including them in domain would imply support, so they're omitted.
 *
 * Used by [GuiSlot.Static] to route per-click-type to different
 * [GuiIntent]s — e.g. `onLeft = "shop.buy"` and `onRight = "shop.preview"`
 * on the same slot.
 */
public enum class GuiClickType {
    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    MIDDLE,
}
