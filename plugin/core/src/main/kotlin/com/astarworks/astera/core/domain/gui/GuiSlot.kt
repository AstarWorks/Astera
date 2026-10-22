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
     * **Click handling** (Wave 5h-fix2): 5 click types are routable
     * independently via [onLeftClick] / [onRightClick] / [onShiftClick] /
     * [onMiddleClick] (`null` for any unmapped type → click is cancelled
     * but no intent dispatched). The Paper adapter inspects
     * `InventoryClickEvent.click` (Bukkit `ClickType`) and picks the right
     * handler — `SHIFT_LEFT` and `SHIFT_RIGHT` both map to [onShiftClick].
     *
     * @property itemId which item to show (resolved via `IItemRegistry`).
     * @property onLeftClick intent emitted on a plain left-click.
     * @property onRightClick intent emitted on a plain right-click (typical
     *   use: preview / info display).
     * @property onShiftClick intent emitted on shift + left-or-right click
     *   (typical use: bulk action, quick-buy).
     * @property onMiddleClick intent emitted on middle-click (rarely useful;
     *   reserve for power-user shortcuts).
     * @property tooltip extra lore lines rendered below the item's vanilla
     *   tooltip. Empty list = no tooltip override. Useful for "what does
     *   this slot do?" hints without baking the help into the item template.
     * @property count how many of the item to show in the stack. Vanilla
     *   inventory stacks cap at 64; the domain enforces `1..64`.
     * @property displayName replaces the item's display name (dynamic rows:
     *   a match, a player). `null` keeps the item template's name.
     * @property lore lines **appended** to the item template's own lore
     *   (e.g. "left-click: give 1" under a weapon's real tooltip).
     * @property headOf when set and the item renders as a player head, the
     *   skin of this player is shown (admin panel player list).
     */
    public data class Static(
        public val itemId: ItemId,
        public val onLeftClick: GuiIntent? = null,
        public val onRightClick: GuiIntent? = null,
        public val onShiftClick: GuiIntent? = null,
        public val onMiddleClick: GuiIntent? = null,
        public val tooltip: List<MessageKey> = emptyList(),
        public val count: Int = 1,
        public val displayName: GuiText? = null,
        public val lore: List<GuiText> = emptyList(),
        public val headOf: com.astarworks.astera.core.domain.player.PlayerId? = null,
    ) : GuiSlot {
        init {
            require(count in 1..MAX_STACK) {
                "GuiSlot.Static.count must be in 1..$MAX_STACK: $count"
            }
        }

        /**
         * Returns the [GuiIntent] to dispatch for the given [clickType], or
         * `null` if no handler is registered for that click. Convenience
         * over the per-field accessors; `PaperGuiListener` uses this to
         * keep the `when (e.click)` block minimal.
         */
        public fun intentFor(clickType: GuiClickType): GuiIntent? = when (clickType) {
            GuiClickType.LEFT -> onLeftClick
            GuiClickType.RIGHT -> onRightClick
            GuiClickType.SHIFT_LEFT, GuiClickType.SHIFT_RIGHT -> onShiftClick
            GuiClickType.MIDDLE -> onMiddleClick
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
        public val tooltip: List<MessageKey> = emptyList(),
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
        public val tooltip: List<MessageKey> = emptyList(),
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
