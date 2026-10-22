package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Pure-function reconciler for the declarative-GUI runtime.
 *
 * Given a [prev] and [next] [GuiSpec], [diff] returns the **smallest reasonable
 * list of [SlotPatch]es** an adapter would need to apply to a real inventory to
 * turn it from `prev`-shape into `next`-shape. The engine is:
 *
 *   - **pure** — no global state, no DI, no side-effects, no logging.
 *   - **adapter-agnostic** — works equally for the Paper inventory adapter and
 *     for the Phase 4 web-admin client.
 *   - **deterministic** — patch list order is stable for fixed inputs (we
 *     walk the union of slot indices in ascending order, with
 *     [SlotPatch.TitleChange] emitted **first** so the adapter can re-open the
 *     container before writing slots if it must).
 *
 * Per-slot decision matrix (using δ's `GuiSlot` sealed hierarchy):
 *
 *   | prev → next                           | output                          |
 *   |---------------------------------------|---------------------------------|
 *   | Empty   ↔ Empty                       | (none)                          |
 *   | Empty   → Static(id, count)           | `Set(idx, id, count)`           |
 *   | Empty   → Border(id)                  | `Set(idx, id, 1)`               |
 *   | Empty   → Reactive(b)                 | `Set(idx, ItemId(b.value), 1)`  |
 *   | Static  ↔ Static (equal)              | (none)                          |
 *   | Static  → Static (diff id/count)      | `Set(idx, id', count')`         |
 *   | Border  ↔ Border (equal)              | (none)                          |
 *   | Border  → Border (diff id)            | `Set(idx, id', 1)`              |
 *   | Reactive ↔ Reactive (equal binding)   | (none)                          |
 *   | Reactive → Reactive (diff binding)    | `Set(idx, ItemId(b'.value), 1)` |
 *   | Confirmation ↔ Confirmation (equal)   | (none)                          |
 *   | Confirmation → Confirmation (changed) | `Set(idx, confirmItemId, 1)`    |
 *   | TextInput ↔ TextInput (equal)         | (none)                          |
 *   | TextInput → TextInput (changed)       | `Set(idx, TEXT_INPUT_SENTINEL,1)`|
 *   | any non-Empty → Empty                 | `Clear(idx)`                    |
 *   | any cross-variant change              | `Set` (treat as replace)        |
 *
 * Confirmation / TextInput note (Wave 5h): the diff engine treats both like
 * `Static` for adapter purposes — the rendered representation is a single
 * primary item (the confirm button for `Confirmation`; a fixed sentinel
 * item id [TEXT_INPUT_SENTINEL_ITEM] for `TextInput`, since the slot has no
 * inherent item — the Paper adapter will swap it for the anvil rename-field
 * at click time in Wave 6b). Click / submit handling is the Paper input-
 * listener layer's job (Wave 6b), orthogonal to diffing.
 *
 * Reactive caveat: the engine ferries the binding's `value` through [ItemId]
 * for now. This works as long as the binding string also validates against
 * [ItemId]'s kebab-case regex; otherwise [ItemId.value] init fails and the
 * load surfaces a typo at load-time rather than corrupting patches at
 * runtime. Stage 2 G will split this into a dedicated `SetReactive` variant
 * once the Paper adapter lands.
 */
public object GuiDiffEngine {

    /**
     * Sentinel item id rendered into a [GuiSlot.TextInput] slot at diff time.
     *
     * Wave 5h has no notion of a "text-field item" — the Paper adapter (Wave
     * 6b) will re-route the slot through an anvil rename-field at click time.
     * The sentinel keeps the diff output stable (so equal-spec → empty-diff
     * still holds) while letting the adapter recognise input-shaped slots by
     * id comparison.
     */
    public val TEXT_INPUT_SENTINEL_ITEM: ItemId = ItemId("gui-text-input-placeholder")

    /**
     * Returns the patch list to transform [prev] into [next]. The result is
     * empty when the two specs are structurally equal.
     *
     * The output order is:
     *   1. `SlotPatch.TitleChange` (at most one)
     *   2. Per-slot patches in ascending `SlotIndex` order
     */
    public fun diff(prev: GuiSpec, next: GuiSpec): List<SlotPatch> {
        if (prev == next) return emptyList()

        val out = mutableListOf<SlotPatch>()

        if (prev.title != next.title) {
            out += SlotPatch.TitleChange(next.title)
        }

        val indices: Set<SlotIndex> = prev.slots.keys + next.slots.keys
        for (idx in indices.sortedBy { it.value }) {
            val before = prev.slotAt(idx)
            val after = next.slotAt(idx)
            patchFor(idx, before, after)?.let(out::add)
        }

        return out
    }

    /**
     * Per-slot decision. Returns null when [before] and [after] are
     * indistinguishable for adapter purposes.
     */
    private fun patchFor(idx: SlotIndex, before: GuiSlot, after: GuiSlot): SlotPatch? {
        // Structural equality covers both "same variant, same fields" and
        // "Empty ↔ Empty" via `data object`.
        if (before == after) return null

        return when (after) {
            is GuiSlot.Empty -> SlotPatch.Clear(idx)
            is GuiSlot.Static -> SlotPatch.Set(
                idx,
                after.itemId,
                after.count,
                slot = after.takeIf { it.displayName != null || it.lore.isNotEmpty() || it.headOf != null },
            )
            is GuiSlot.Border -> SlotPatch.Set(idx, after.itemId, count = 1)
            is GuiSlot.Reactive -> SlotPatch.Set(
                idx,
                // Adapter re-resolves through IItemRegistry; we ferry the
                // binding's string identity through ItemId for now. Replaced
                // by a dedicated SetReactive patch when Stage 2 G lands.
                ItemId(after.binding.value),
                count = 1,
            )
            // Wave 5h: render the confirm-button item in the slot; the cancel
            // item is the Paper input layer's job at click time (Wave 6b).
            is GuiSlot.Confirmation -> SlotPatch.Set(idx, after.confirmItemId, count = 1)
            // Wave 5h: emit the sentinel item; the Paper adapter (Wave 6b)
            // re-routes the slot through an anvil rename-field at click time.
            // MessageKey allows dots (ItemId does not), so we cannot ferry
            // `placeholderKey.value` directly the way Reactive ferries
            // BindingKey — a stable sentinel keeps diffs deterministic
            // without coupling to a future runtime registry lookup.
            is GuiSlot.TextInput -> SlotPatch.Set(idx, TEXT_INPUT_SENTINEL_ITEM, count = 1)
        }
    }
}
