package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.domain.dialog.DialogAfterAction
import com.astarworks.astera.core.domain.dialog.DialogBody
import com.astarworks.astera.core.domain.dialog.DialogButton
import com.astarworks.astera.core.domain.dialog.DialogId
import com.astarworks.astera.core.domain.dialog.DialogInput
import com.astarworks.astera.core.domain.dialog.DialogSpec
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import java.util.UUID

/**
 * DSL marker for the [DialogSpec] Kotlin DSL (Wave 5i Worker γ, ADR-0096).
 *
 * Scopes the receivers so an inner builder cannot accidentally call methods
 * on the outer builder (e.g. calling `title(...)` inside a `buttons { }`
 * block would be a compile error rather than silently overwriting the spec
 * title). Mirrors Kotlin's standard `@HtmlTagMarker` / `@kotlinx.html.HtmlTagMarker`
 * pattern.
 */
@DslMarker
public annotation class DialogDsl

/**
 * Entry point of the [DialogSpec] DSL.
 *
 * The mandatory [titleKey] is required up-front because every dialog must
 * have a title (Paper's `Dialog.Builder.title()` is non-null). The rest of
 * the spec is built inside [block]:
 *
 * ```
 * val spec = dialogSpec("astera.shop.confirm.title") {
 *     body {
 *         text("astera.shop.confirm.prompt")
 *         itemPreview("iron-sword")
 *     }
 *     inputs {
 *         number("quantity", "astera.shop.confirm.qty", min = 1f, max = 64f) {
 *             initial(1f)
 *         }
 *     }
 *     buttons {
 *         button("astera.shop.confirm.buy") {
 *             intent("shop.purchase.confirm") {
 *                 "offer-id"("offer-42")
 *             }
 *         }
 *         button("astera.shop.confirm.cancel") {
 *             intent("gui.close")
 *         }
 *     }
 * }
 * ```
 *
 * Dialog domain shape lives in
 * [com.astarworks.astera.core.domain.dialog]; this DSL is a thin
 * application-layer convenience that emits the same value classes.
 */
public fun dialogSpec(
    titleKey: String,
    block: DialogSpecBuilder.() -> Unit,
): DialogSpec = DialogSpecBuilder().apply {
    title(titleKey)
    block()
}.build()

/**
 * Top-level [DialogSpec] builder. Prefer the [dialogSpec] convenience entry
 * point; this is exposed only so callers can build a spec incrementally
 * outside a DSL block when needed.
 */
@DialogDsl
public class DialogSpecBuilder {
    private var id: DialogId = DialogId.random()
    private var titleKey: MessageKey? = null
    private val body: MutableList<DialogBody> = mutableListOf()
    private val inputs: MutableList<DialogInput> = mutableListOf()
    private val buttons: MutableList<DialogButton> = mutableListOf()
    private var afterAction: DialogAfterAction = DialogAfterAction.CloseDialog

    /** Override the random default [DialogId] with a caller-supplied one. */
    public fun id(value: DialogId) {
        id = value
    }

    /** Convenience: parse a string UUID into a [DialogId]. */
    public fun id(value: String) {
        id = DialogId(UUID.fromString(value))
    }

    /** Set the dialog title from a raw i18n key string. */
    public fun title(key: String) {
        titleKey = MessageKey(key)
    }

    /** Set the dialog title from a pre-validated [MessageKey]. */
    public fun title(key: MessageKey) {
        titleKey = key
    }

    /** Override the default [DialogAfterAction.CloseDialog] behaviour. */
    public fun afterAction(action: DialogAfterAction) {
        afterAction = action
    }

    /** Append body elements (plain text / item preview) via a nested DSL. */
    public fun body(block: DialogBodyBuilder.() -> Unit) {
        body += DialogBodyBuilder().apply(block).build()
    }

    /** Append form inputs (text / number / bool / select) via a nested DSL. */
    public fun inputs(block: DialogInputsBuilder.() -> Unit) {
        inputs += DialogInputsBuilder().apply(block).build()
    }

    /** Append clickable buttons via a nested DSL. */
    public fun buttons(block: DialogButtonsBuilder.() -> Unit) {
        buttons += DialogButtonsBuilder().apply(block).build()
    }

    internal fun build(): DialogSpec {
        val resolvedTitle = titleKey
            ?: error("DialogSpec requires title(...) — call title(\"astera.<area>.title\") before build().")
        return DialogSpec(
            id = id,
            titleKey = resolvedTitle,
            body = body.toList(),
            inputs = inputs.toList(),
            buttons = buttons.toList(),
            afterAction = afterAction,
        )
    }
}

/**
 * Body-section sub-builder. Each call appends an element; ordering is
 * preserved in the resulting [DialogSpec.body] list.
 */
@DialogDsl
public class DialogBodyBuilder {
    private val items: MutableList<DialogBody> = mutableListOf()

    /** Append a plain-text line. */
    public fun text(messageKey: String) {
        items += DialogBody.PlainText(MessageKey(messageKey))
    }

    /** Append a plain-text line from a pre-built [MessageKey]. */
    public fun text(messageKey: MessageKey) {
        items += DialogBody.PlainText(messageKey)
    }

    /** Append an item preview (icon + tooltip resolved by the Wave 6b adapter). */
    public fun itemPreview(itemId: String) {
        items += DialogBody.ItemPreview(ItemId(itemId))
    }

    /** Append an item preview from a pre-built [ItemId]. */
    public fun itemPreview(itemId: ItemId) {
        items += DialogBody.ItemPreview(itemId)
    }

    internal fun build(): List<DialogBody> = items.toList()
}

/**
 * Inputs-section sub-builder. Each helper maps to one [DialogInput] sealed
 * variant; ordering is preserved in [DialogSpec.inputs].
 */
@DialogDsl
public class DialogInputsBuilder {
    private val items: MutableList<DialogInput> = mutableListOf()

    /**
     * Free-form text field. Defaults `maxLength = 64`, single-line.
     * Use [TextFieldBuilder.maxLength] / [TextFieldBuilder.multiline] / [TextFieldBuilder.initial]
     * inside [block] to override.
     */
    public fun text(
        key: String,
        labelKey: String,
        block: TextFieldBuilder.() -> Unit = {},
    ) {
        items += TextFieldBuilder(key, labelKey).apply(block).build()
    }

    /**
     * Numeric slider field. The `min..max` range is mandatory (Paper API
     * requires it); [NumberFieldBuilder.initial] and [NumberFieldBuilder.step]
     * override the defaults of `min` / `1.0f`.
     */
    public fun number(
        key: String,
        labelKey: String,
        min: Float,
        max: Float,
        block: NumberFieldBuilder.() -> Unit = {},
    ) {
        items += NumberFieldBuilder(key, labelKey, min, max).apply(block).build()
    }

    /** Boolean checkbox / toggle field. */
    public fun bool(key: String, labelKey: String, initial: Boolean = false) {
        items += DialogInput.BooleanField(key, MessageKey(labelKey), initial)
    }

    /**
     * Single-option picker. The [block] MUST add at least one
     * [SingleSelectBuilder.option] entry, otherwise [DialogInput.SingleSelect]
     * construction fails.
     */
    public fun select(
        key: String,
        labelKey: String,
        block: SingleSelectBuilder.() -> Unit,
    ) {
        items += SingleSelectBuilder(key, labelKey).apply(block).build()
    }

    internal fun build(): List<DialogInput> = items.toList()
}

/**
 * Builder for [DialogInput.TextField]. Defaults match the domain class:
 * empty initial, `maxLength = 64`, single-line.
 */
@DialogDsl
public class TextFieldBuilder(
    private val key: String,
    private val labelKey: String,
) {
    private var initial: String = ""
    private var maxLength: Int = 64
    private var multiline: Boolean = false

    public fun initial(value: String) {
        initial = value
    }

    public fun maxLength(value: Int) {
        maxLength = value
    }

    public fun multiline(value: Boolean = true) {
        multiline = value
    }

    internal fun build(): DialogInput.TextField = DialogInput.TextField(
        key = key,
        labelKey = MessageKey(labelKey),
        initial = initial,
        maxLength = maxLength,
        multiline = multiline,
    )
}

/**
 * Builder for [DialogInput.NumberField]. The `min..max` range is captured
 * positionally; [initial] defaults to `min` and [step] defaults to `1.0f`,
 * mirroring the domain defaults.
 */
@DialogDsl
public class NumberFieldBuilder(
    private val key: String,
    private val labelKey: String,
    private val min: Float,
    private val max: Float,
) {
    private var initial: Float = min
    private var step: Float = 1.0f

    public fun initial(value: Float) {
        initial = value
    }

    public fun step(value: Float) {
        step = value
    }

    internal fun build(): DialogInput.NumberField = DialogInput.NumberField(
        key = key,
        labelKey = MessageKey(labelKey),
        min = min,
        max = max,
        initial = initial,
        step = step,
    )
}

/**
 * Builder for [DialogInput.SingleSelect]. At least one [option] call is
 * required (the underlying domain constructor enforces it). The optional
 * [initial] picks the pre-selected option key — must match one of the added
 * options' keys.
 */
@DialogDsl
public class SingleSelectBuilder(
    private val key: String,
    private val labelKey: String,
) {
    private val options: MutableList<DialogInput.SingleSelect.SelectOption> = mutableListOf()
    private var initialKey: String? = null

    /** Add a selectable option. [optionKey] is returned as the response value. */
    public fun option(optionKey: String, labelKey: String) {
        options += DialogInput.SingleSelect.SelectOption(optionKey, MessageKey(labelKey))
    }

    /** Pre-select the option whose `optionKey == value`. */
    public fun initial(value: String) {
        initialKey = value
    }

    internal fun build(): DialogInput.SingleSelect = DialogInput.SingleSelect(
        key = key,
        labelKey = MessageKey(labelKey),
        options = options.toList(),
        initialKey = initialKey,
    )
}

/**
 * Buttons-section sub-builder. Ordering is preserved in [DialogSpec.buttons],
 * and the index is what `FakeDialogGateway.simulateResponse(buttonIndex = ...)`
 * uses.
 */
@DialogDsl
public class DialogButtonsBuilder {
    private val items: MutableList<DialogButton> = mutableListOf()

    /**
     * Append a clickable button. The [block] MUST call [DialogButtonBuilder.intent]
     * — a button without an intent has no behaviour.
     */
    public fun button(labelKey: String, block: DialogButtonBuilder.() -> Unit) {
        items += DialogButtonBuilder(labelKey).apply(block).build()
    }

    internal fun build(): List<DialogButton> = items.toList()
}

/**
 * Builder for a single [DialogButton]. [intent] is mandatory; [tooltip] and
 * [width] are optional (defaults match the domain class: no tooltip, 150 px).
 */
@DialogDsl
public class DialogButtonBuilder(private val labelKey: String) {
    private var tooltipKey: MessageKey? = null
    private var widthPx: Int = 150
    private var intent: GuiIntent? = null

    /** Set the hover-text i18n key. */
    public fun tooltip(key: String) {
        tooltipKey = MessageKey(key)
    }

    /** Set the button width hint (Paper enforces 1..400 px). */
    public fun width(px: Int) {
        widthPx = px
    }

    /**
     * Bind the button's [GuiIntent]. Inside [payloadBlock], use the
     * `"key"(value)` invoke-string syntax to add payload entries:
     *
     * ```
     * intent("shop.purchase.confirm") {
     *     "offer-id"("offer-42")
     *     "qty"("3")
     * }
     * ```
     */
    public fun intent(key: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        val payload = PayloadBuilder().apply(payloadBlock).build()
        intent = GuiIntent(key, payload)
    }

    /** Bind a pre-built [GuiIntent] (escape hatch for shared intents). */
    public fun intent(value: GuiIntent) {
        intent = value
    }

    internal fun build(): DialogButton {
        val resolvedIntent = intent
            ?: error(
                "DialogButton '$labelKey' requires intent(...) — every button must dispatch a GuiIntent on click.",
            )
        return DialogButton(
            labelKey = MessageKey(labelKey),
            tooltipKey = tooltipKey,
            widthPx = widthPx,
            intent = resolvedIntent,
        )
    }
}

// PayloadBuilder is reused from GuiSpecBuilder.kt (same package, internal
// constructor). The DSL surface intentionally hands it to the dialog
// `intent(...)` builder so writers use the same `"key"(value)` operator
// pattern as the GUI slot intents.

