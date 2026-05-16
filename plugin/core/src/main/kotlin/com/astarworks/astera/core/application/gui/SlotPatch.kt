package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Minimal edit operation produced by [GuiDiffEngine] to transform the
 * previously-rendered [com.astarworks.astera.core.domain.gui.GuiSpec] into the next
 * one.
 *
 * The Paper adapter (Stage 2 G) applies these against a Bukkit `Inventory` so
 * that **only changed cells round-trip** — the player's hot-bar / cursor stay
 * untouched, and there is no full inventory rebuild every state tick. This is
 * the React-style reconciliation contract for our GUI runtime.
 *
 * Patches are **values**, not commands — they hold no lambdas and no adapter
 * references, so the same patch list can be serialised and re-played by the
 * Phase 4 web admin UI (the strategic motivation for the declarative-GUI
 * pivot — see plan §"ユーザ方針 #2").
 */
public sealed interface SlotPatch {

    /**
     * Set the slot at [index] to a stack of [itemId] × [count]. Used for both
     * Empty → non-Empty transitions and for re-renders where the resolved item
     * differs from the previous frame.
     *
     * For `GuiSlot.Reactive` transitions, the engine carries the binding's
     * `value` as the [itemId] — this is legal as long as the binding's lexical
     * form (kebab-case, no dots) is a valid [ItemId]. Bindings that include
     * dots or underscores (which [com.astarworks.astera.core.domain.gui.BindingKey]
     * allows but [ItemId] does not) will fail the [ItemId] constructor and
     * surface as a load-time error rather than corrupt patches at runtime.
     * Stage 2 G is expected to split this into a dedicated `SetReactive`
     * variant once the Paper adapter wiring lands.
     */
    public data class Set(
        val index: SlotIndex,
        val itemId: ItemId,
        val count: Int = 1,
    ) : SlotPatch {
        init {
            require(count in 1..MAX_STACK) { "Set.count must be in 1..$MAX_STACK: $count" }
        }

        public companion object {
            /** Vanilla maximum stack size — mirrors `GuiSlot.Static.MAX_STACK`. */
            public const val MAX_STACK: Int = 64
        }
    }

    /**
     * Clear the slot at [index] back to empty / air. Used when a previously
     * non-empty slot becomes [com.astarworks.astera.core.domain.gui.GuiSlot.Empty].
     */
    public data class Clear(val index: SlotIndex) : SlotPatch

    /**
     * Re-render the inventory title. Emitted at most once per diff (titles do
     * not slot-merge in vanilla Minecraft; the adapter typically re-opens the
     * container).
     */
    public data class TitleChange(val title: MessageKey) : SlotPatch
}
