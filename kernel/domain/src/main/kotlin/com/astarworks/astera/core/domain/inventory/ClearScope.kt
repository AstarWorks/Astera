package com.astarworks.astera.core.domain.inventory

/**
 * Which slot range an `IInventoryMutator.clear` call should affect
 * (Wave 5world-events, ADR-0111).
 *
 * Sealed because [Slot] carries a slot index — the rest are scope tokens with
 * no payload. Pulling the variant choice into a type (vs. an `IntRange`
 * argument) keeps caller intent visible in the use-case layer and makes
 * Konsist-time checking practical.
 *
 * Slot indices match the canonical Minecraft layout that every adapter (Paper,
 * Velocity passthrough) is expected to honour:
 *
 *  - 0..8 = hotbar (`Hotbar`)
 *  - 9..35 = main inventory rows (`MainInventory`)
 *  - 36..39 = armor (`Armor`)
 *  - 40 = off-hand (`OffHand`)
 *
 * Adapter code is allowed to translate the canonical index into the platform's
 * native slot id; the domain stays free of any platform constant.
 */
public sealed interface ClearScope {

    /** Wipe everything addressable — hotbar + main + armor + off-hand. */
    public data object All : ClearScope

    /** Clear hotbar slots only (indices 0..8). */
    public data object Hotbar : ClearScope

    /** Clear main-inventory rows only (indices 9..35). */
    public data object MainInventory : ClearScope

    /** Clear armor slots only (indices 36..39). */
    public data object Armor : ClearScope

    /** Clear off-hand only (index 40). */
    public data object OffHand : ClearScope

    /**
     * Clear a single slot at [slotIndex]. The caller is responsible for
     * supplying a valid index — the mutator surfaces an
     * [com.astarworks.astera.core.domain.inventory.InventoryError.InvalidSlotIndex]
     * for out-of-range values.
     */
    public data class Slot(public val slotIndex: Int) : ClearScope {
        init {
            require(slotIndex in 0..40) {
                "slotIndex must be 0..40 (Minecraft canonical range): $slotIndex"
            }
        }
    }
}
