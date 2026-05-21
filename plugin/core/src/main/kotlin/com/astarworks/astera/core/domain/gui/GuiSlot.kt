package com.astarworks.astera.core.domain.gui

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

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

    /**
     * Two-button confirmation prompt (Wave 5h, ADR-0079 §2).
     *
     * Models the "are you sure?" dialog used by shop purchases, settings
     * reset, etc. The Paper adapter (Wave 6b) renders this as a 2-item row
     * (confirm / cancel) with the prompt rendered via [promptKey] in the
     * title bar; non-Paper adapters are free to render however they like.
     *
     * Both [confirmIntent] and [cancelIntent] are routed through the same
     * `GuiIntentDispatcher` as [Static.onClick] — the adapter simply emits
     * one or the other based on which item the player clicks.
     *
     * @property promptKey i18n key for the prompt text (e.g.
     *   `astera.gui.shop.confirm-purchase`).
     * @property confirmItemId item shown for "yes" (e.g. green wool).
     * @property cancelItemId item shown for "no" (e.g. red wool).
     * @property confirmIntent intent emitted on confirm click.
     * @property cancelIntent intent emitted on cancel click.
     */
    public data class Confirmation(
        public val promptKey: MessageKey,
        public val confirmItemId: ItemId,
        public val cancelItemId: ItemId,
        public val confirmIntent: GuiIntent,
        public val cancelIntent: GuiIntent,
    ) : GuiSlot

    /**
     * Free-text input field (Wave 5h, ADR-0079 §2).
     *
     * Models a single-line text input — used for shop search, loadout
     * renaming, report submission, chat alias editing, etc. The submitted
     * value arrives in the [onSubmitIntent]'s payload under key `"text"`,
     * preserving the "intents are pure values" invariant from [GuiIntent].
     *
     * The Paper adapter (Wave 6b) renders this as an anvil GUI (the only
     * vanilla container that exposes a text field); non-Paper adapters
     * may render as an HTML `<input>` or platform-native field.
     *
     * @property placeholderKey i18n key for the placeholder shown when the
     *   field is empty.
     * @property maxLength clamp on input length. Required `1..128` — the
     *   upper bound is generous enough for short report text yet short
     *   enough to keep storage / display predictable. Defaults to 32 to
     *   match the loadout-name length budget.
     * @property onSubmitIntent intent emitted when the player confirms the
     *   input. The submitted text arrives under payload key `"text"`.
     */
    public data class TextInput(
        public val placeholderKey: MessageKey,
        public val maxLength: Int = 32,
        public val onSubmitIntent: GuiIntent,
    ) : GuiSlot {
        init {
            require(maxLength in 1..MAX_TEXT_LENGTH) {
                "GuiSlot.TextInput.maxLength must be in 1..$MAX_TEXT_LENGTH, was $maxLength"
            }
        }

        public companion object {
            /** Upper bound on [maxLength]. Anvil GUI fits ~30 chars comfortably;
             *  128 leaves room for non-Paper adapters that render wider. */
            public const val MAX_TEXT_LENGTH: Int = 128
        }
    }
}
