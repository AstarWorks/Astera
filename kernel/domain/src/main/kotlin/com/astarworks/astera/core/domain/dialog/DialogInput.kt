package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Form input field on a [DialogSpec] (Wave 5i, ADR-0096).
 *
 * Sealed 4 variants matching Paper's
 * `TextDialogInput / NumberRangeDialogInput / BooleanDialogInput /
 * SingleOptionDialogInput`. Wave 6b's `PaperDialogGateway` translates each
 * variant into its Paper builder counterpart; this domain shape is
 * intentionally Paper-agnostic so the GUI/Dialog DSL (Wave 5i workers β/γ)
 * and the test fakes can manipulate dialog specs without touching Bukkit.
 *
 * Response values arrive in [DialogResponse.inputValues] keyed by [key].
 * All values are stringified at the adapter boundary:
 *  - Text → raw string
 *  - Number → `"%.2f"` via `Locale.ROOT`
 *  - Boolean → `"true"` / `"false"`
 *  - SingleSelect → the chosen [SingleSelect.SelectOption.key]
 *
 * Field-level constraints are validated at construction so YAML / DSL typos
 * surface immediately rather than failing inside the Paper adapter.
 */
public sealed interface DialogInput {
    public val key: String

    /**
     * Free-form text input.
     *
     * @property initial pre-filled value (default empty).
     * @property maxLength hard cap, 1..256 (Paper's own ceiling). Defaults to 64.
     * @property multiline allow newlines in the input box (Paper's
     *   `TextDialogInput.multiline`). Defaults to `false` (single-line input).
     */
    public data class TextField(
        public override val key: String,
        public val labelKey: MessageKey,
        public val initial: String = "",
        public val maxLength: Int = 64,
        public val multiline: Boolean = false,
    ) : DialogInput {
        init {
            require(maxLength in 1..256) {
                "DialogInput.TextField.maxLength must be in 1..256, was $maxLength"
            }
        }
    }

    /**
     * Numeric slider input. Paper exposes `Float` granularity (not `Double`).
     *
     * @property min inclusive lower bound.
     * @property max inclusive upper bound; must be `>= min`.
     * @property initial pre-selected value; defaults to [min], must be in `min..max`.
     * @property step granularity of the slider; must be `> 0`.
     */
    public data class NumberField(
        public override val key: String,
        public val labelKey: MessageKey,
        public val min: Float,
        public val max: Float,
        public val initial: Float = min,
        public val step: Float = 1.0f,
    ) : DialogInput {
        init {
            require(min <= max) {
                "DialogInput.NumberField.min ($min) must be <= max ($max)"
            }
            require(initial in min..max) {
                "DialogInput.NumberField.initial ($initial) must be in [$min, $max]"
            }
            require(step > 0f) {
                "DialogInput.NumberField.step must be > 0, was $step"
            }
        }
    }

    /**
     * On/off toggle.
     *
     * @property initial pre-selected state (default `false`).
     */
    public data class BooleanField(
        public override val key: String,
        public val labelKey: MessageKey,
        public val initial: Boolean = false,
    ) : DialogInput

    /**
     * Picker over a fixed list of options.
     *
     * @property options non-empty list of selectable entries.
     * @property initialKey if non-null, must match one of [options]'
     *   [SelectOption.key]. `null` means "no pre-selection".
     */
    public data class SingleSelect(
        public override val key: String,
        public val labelKey: MessageKey,
        public val options: List<SelectOption>,
        public val initialKey: String? = null,
    ) : DialogInput {
        init {
            require(options.isNotEmpty()) {
                "DialogInput.SingleSelect.options must not be empty"
            }
            if (initialKey != null) {
                require(options.any { it.key == initialKey }) {
                    "DialogInput.SingleSelect.initialKey '$initialKey' not present in options"
                }
            }
        }

        /**
         * Single picker entry. [key] is the stable id returned in
         * [DialogResponse.inputValues]; [labelKey] is the i18n message shown
         * to the player.
         */
        public data class SelectOption(
            public val key: String,
            public val labelKey: MessageKey,
        )
    }
}
