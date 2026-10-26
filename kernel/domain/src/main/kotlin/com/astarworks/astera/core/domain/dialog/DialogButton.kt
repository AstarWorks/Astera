package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Clickable button at the bottom of a dialog (Paper `ActionButton`).
 *
 * The [intent] is dispatched when the player clicks — input values from the
 * form are merged into the intent's payload by the gateway adapter (Wave 6b
 * `PaperDialogGateway`) so the handler receives both the button choice and
 * the form data in one place.
 *
 * @property labelKey i18n key for the button text.
 * @property tooltipKey optional hover-text i18n key.
 * @property widthPx button width hint (Paper default = 150, max ~400).
 * @property intent the [GuiIntent] dispatched on click. Routed through the
 *   same `IGuiIntentRouter` as GUI slot clicks.
 */
public data class DialogButton(
    public val labelKey: MessageKey,
    public val tooltipKey: MessageKey? = null,
    public val widthPx: Int = 150,
    public val intent: GuiIntent,
) {
    init {
        require(widthPx in 1..400) { "DialogButton.widthPx must be in 1..400, was $widthPx" }
    }
}
