package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Player response to a [DialogSpec] (Wave 5i, ADR-0096).
 *
 * Wave 6b `PaperDialogGateway` builds this from Paper `DialogResponseView`
 * + the clicked `ActionButton`'s intent. The adapter MERGES the input
 * values into the button's intent payload (so handlers get both the
 * "which button" and "what did you type" in [triggeredButtonIntent.payload]
 * + [inputValues] respectively).
 *
 * @property inputValues stringified form values keyed by [DialogInput.key].
 *   Boolean → "true"/"false", Number → "%.2f" via Locale.ROOT.
 */
public data class DialogResponse(
    public val playerId: PlayerId,
    public val dialogId: DialogId,
    public val triggeredButtonIntent: GuiIntent,
    public val inputValues: Map<String, String>,
)
