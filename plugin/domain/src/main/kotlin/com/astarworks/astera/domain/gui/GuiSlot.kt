package com.astarworks.astera.domain.gui

import com.astarworks.astera.domain.model.item.ItemId

/**
 * What one slot of a [GuiSpec] looks like and how it reacts to a click.
 *
 * Sealed so the adapter's render `when` is exhaustive — adding a new variant
 * is a deliberate cross-cutting change rather than something a contributor can
 * miss.
 *
 * Click actions are [GuiIntent] **values**, never lambdas — see [GuiIntent]
 * for the rationale. Stage 2 L will add a Konsist rule that fails the build
 * if any GUI-domain file references a `Function*` type so this property
 * cannot regress.
 */
public sealed interface GuiSlot {

    /**
     * A fixed item rendered into the slot.
     *
     * @property itemId which item to show (resolved via `IItemRegistry`).
     * @property onClick the intent emitted when the player clicks; `null`
     *   means the slot is decorative / non-interactive (vanilla click is
     *   simply cancelled by the adapter).
     * @property count how many of the item to show in the stack. Vanilla
     *   inventory stacks cap at 64, but some items (potions, eggs) cap lower;
     *   the domain only enforces `1..64` so adapters can clamp further.
     */
    public data class Static(
        public val itemId: ItemId,
        public val onClick: GuiIntent? = null,
        public val count: Int = 1,
    ) : GuiSlot {
        init {
            require(count in 1..MAX_STACK) {
                "GuiSlot.Static.count must be in 1..$MAX_STACK: $count"
            }
        }

        public companion object {
            /** Vanilla maximum stack size. */
            public const val MAX_STACK: Int = 64
        }
    }

    /**
     * A slot whose item / display is computed from a live data source at
     * render time. The [binding] names what to subscribe to; the application
     * layer resolves it via the view-model passed when the GUI is opened.
     *
     * Reactives are read-only from the player's perspective — clicks on a
     * reactive slot have no effect by default. To emit an intent from a
     * dynamic slot, the application layer can wrap the resolved value in a
     * [Static] at render time.
     */
    public data class Reactive(
        public val binding: BindingKey,
    ) : GuiSlot

    /**
     * The slot is intentionally empty. Equivalent to "no entry in
     * [GuiSpec.slots]", but spelling it out is useful for layouts where
     * "empty" is a deliberate gap and not just absence.
     */
    public data object Empty : GuiSlot

    /**
     * Decorative border / pane / spacer. Renders [itemId] with no click
     * behaviour. Convenience over `Static(itemId, onClick = null)` for the
     * extremely common "fill the edges with a coloured glass pane" idiom.
     */
    public data class Border(
        public val itemId: ItemId,
    ) : GuiSlot
}
