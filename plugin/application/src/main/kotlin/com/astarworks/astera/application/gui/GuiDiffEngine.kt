package com.astarworks.astera.application.gui

import com.astarworks.astera.domain.gui.GuiSlot
import com.astarworks.astera.domain.gui.GuiSpec
import com.astarworks.astera.domain.gui.SlotIndex
import com.astarworks.astera.domain.model.item.ItemId

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
 *   | prev → next                          | output                         |
 *   |--------------------------------------|--------------------------------|
 *   | Empty   ↔ Empty                      | (none)                         |
 *   | Empty   → Static(id, count)          | `Set(idx, id, count)`          |
 *   | Empty   → Border(id)                 | `Set(idx, id, 1)`              |
 *   | Empty   → Reactive(b)                | `Set(idx, ItemId(b.value), 1)` |
 *   | Static  ↔ Static (equal)             | (none)                         |
 *   | Static  → Static (diff id/count)     | `Set(idx, id', count')`        |
 *   | Border  ↔ Border (equal)             | (none)                         |
 *   | Border  → Border (diff id)           | `Set(idx, id', 1)`             |
 *   | Reactive ↔ Reactive (equal binding)  | (none)                         |
 *   | Reactive → Reactive (diff binding)   | `Set(idx, ItemId(b'.value), 1)`|
 *   | any non-Empty → Empty                | `Clear(idx)`                   |
 *   | any cross-variant change             | `Set` (treat as replace)       |
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
            is GuiSlot.Static -> SlotPatch.Set(idx, after.itemId, after.count)
            is GuiSlot.Border -> SlotPatch.Set(idx, after.itemId, count = 1)
            is GuiSlot.Reactive -> SlotPatch.Set(
                idx,
                // Adapter re-resolves through IItemRegistry; we ferry the
                // binding's string identity through ItemId for now. Replaced
                // by a dedicated SetReactive patch when Stage 2 G lands.
                ItemId(after.binding.value),
                count = 1,
            )
        }
    }
}
