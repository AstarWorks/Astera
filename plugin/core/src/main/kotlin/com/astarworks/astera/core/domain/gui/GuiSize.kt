package com.astarworks.astera.core.domain.gui

/**
 * Sizes of a GUI inventory. Matches the vanilla shapes the adapter layer can
 * actually render.
 *
 * Capacity ([slotCount]) is authoritative: a `CHEST_27` GUI accepts
 * `SlotIndex(0)..SlotIndex(26)` and nothing else. The domain enforces this in
 * [GuiSpec]; the adapter trusts the bound.
 *
 * Chest sizes come in multiples of 9 (one chest row = 9 slots). The hopper is
 * a single 5-wide row — it's enumerated explicitly rather than parameterised
 * because there's no other "wide-1-row" container in vanilla.
 */
public enum class GuiSize(public val slotCount: Int) {
    CHEST_9(9),
    CHEST_18(18),
    CHEST_27(27),
    CHEST_36(36),
    CHEST_45(45),
    CHEST_54(54),
    HOPPER_5(5),
    ;

    /** True when [index] is a valid slot index for a GUI of this size. */
    public fun contains(index: SlotIndex): Boolean = index.value in 0 until slotCount
}
