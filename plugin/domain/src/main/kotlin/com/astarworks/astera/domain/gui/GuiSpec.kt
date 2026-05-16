package com.astarworks.astera.domain.gui

import com.astarworks.astera.domain.model.i18n.MessageKey
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Declarative description of a GUI screen.
 *
 * ### Shape
 *
 * - [id] — stable handle, used to open / address the GUI.
 * - [title] — i18n key for the inventory title (rendered through
 *   `IMessageRenderer` in the adapter).
 * - [size] — inventory dimensions; bounds-checks every slot index at
 *   construction.
 * - [slots] — sparse `SlotIndex -> GuiSlot` map. Indices not present render
 *   as [GuiSlot.Empty]. Backed by a [PersistentMap] so structural updates
 *   are cheap and the spec is safe to share across threads (the rendered
 *   GUI lives on the main tick thread; intent handlers may run on async
 *   dispatchers per the Scheduler ADR).
 *
 * ### Construction patterns
 *
 * GUI specs are built **once** at content-load time (or by use cases that
 * compose a one-shot dialog) and are immutable thereafter. Use the [Builder]
 * for ergonomic construction:
 *
 * ```
 * val spec = GuiSpec.build(GuiId("shop-main"), MessageKey("astera.gui.shop"), GuiSize.CHEST_27) {
 *     slot(SlotIndex(0), GuiSlot.Border(ItemId("gui-pane-dark")))
 *     slot(SlotIndex(13), GuiSlot.Static(ItemId("iron-sword"), GuiIntent("shop.purchase", mapOf("itemId" to "iron-sword"))))
 * }
 * ```
 *
 * ### Domain purity
 *
 * No Bukkit, no serialization annotations, no lambdas inside the data — see
 * [GuiIntent] for why click handlers are values. Adapter / application layers
 * (Stage 2 G) turn `GuiSpec` into Bukkit inventories and route `GuiIntent`s.
 */
public data class GuiSpec(
    public val id: GuiId,
    public val title: MessageKey,
    public val size: GuiSize,
    public val slots: PersistentMap<SlotIndex, GuiSlot> = persistentMapOf(),
) {
    init {
        // Every populated slot must be reachable within the chosen size. This
        // is the GuiSize-aware upper-bound check that SlotIndex itself can't
        // do (SlotIndex only enforces non-negative).
        slots.keys.forEach { idx ->
            require(size.contains(idx)) {
                "SlotIndex $idx out of bounds for $size (max=${size.slotCount - 1})"
            }
        }
    }

    /** Look up what to render at [index]; absent entries are [GuiSlot.Empty]. */
    public fun slotAt(index: SlotIndex): GuiSlot = slots[index] ?: GuiSlot.Empty

    /**
     * Mutator-style copy returning a new spec with [index] -> [slot] applied.
     * Cheap thanks to structural sharing in [PersistentMap].
     */
    public fun withSlot(index: SlotIndex, slot: GuiSlot): GuiSpec {
        require(size.contains(index)) {
            "SlotIndex $index out of bounds for $size (max=${size.slotCount - 1})"
        }
        return copy(slots = slots.put(index, slot))
    }

    /**
     * Ergonomic builder. Keeps construction declarative and validates each
     * slot index against [size] as it is added, surfacing layout bugs at the
     * exact call site rather than in the final `init { }` lump.
     */
    public class Builder internal constructor(
        public val id: GuiId,
        public val title: MessageKey,
        public val size: GuiSize,
    ) {
        private val entries: MutableMap<SlotIndex, GuiSlot> = mutableMapOf()

        public fun slot(index: SlotIndex, slot: GuiSlot): Builder = apply {
            require(size.contains(index)) {
                "SlotIndex $index out of bounds for $size (max=${size.slotCount - 1})"
            }
            entries[index] = slot
        }

        internal fun build(): GuiSpec =
            GuiSpec(id, title, size, entries.toPersistentMap())
    }

    public companion object {
        /** Builder-style factory. */
        public fun build(
            id: GuiId,
            title: MessageKey,
            size: GuiSize,
            block: Builder.() -> Unit,
        ): GuiSpec = Builder(id, title, size).apply(block).build()
    }
}
