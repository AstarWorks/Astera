package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.gui.SlotIndex

/**
 * Where to anchor a modal / popup / tooltip overlay
 * (Wave 5gui-depth, ADR-0114).
 *
 * Vendor-neutral by design — the Paper adapter (Wave 6) translates anchor
 * variants to actual screen / inventory coordinates. Anvil-based prompts pin
 * to [Center]; cursor-tracking tooltips use [Cursor]; per-slot overlays use
 * [NearSlot]; HUD-corner toasts use [TopLeft] / [TopRight].
 *
 * Sealed so the adapter's render `when` is exhaustive.
 */
public sealed interface AnchorPosition {

    /** Centre of the screen / overlay region. */
    public data object Center : AnchorPosition

    /** Top-left corner. Typical for stack-of-toasts notifications. */
    public data object TopLeft : AnchorPosition

    /** Top-right corner. Typical for achievement / score popups. */
    public data object TopRight : AnchorPosition

    /**
     * Pinned to the player's cursor. The adapter follows the cursor each
     * tick; useful for inspector-style tooltips.
     */
    public data object Cursor : AnchorPosition

    /**
     * Pinned beside a specific GUI slot. The adapter picks an offset that
     * keeps the overlay inside the inventory bounds (Wave 6 detail).
     */
    public data class NearSlot(public val slotIndex: SlotIndex) : AnchorPosition
}
