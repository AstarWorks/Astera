package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.gui.GuiIntent

/**
 * Why a dialog disappeared from a player's screen (Wave 5i, ADR-0096).
 *
 * Carried by `DialogClosed` so subscribers (audit, analytics, tutorial state
 * machines) can distinguish a deliberate confirmation click from a
 * server-side force-close or a player disconnect mid-prompt.
 *
 * Sealed so the Wave 6b adapter's `when` is exhaustive.
 */
public sealed interface DialogCloseReason {
    /**
     * Player clicked one of the dialog's buttons. The triggering button's
     * [GuiIntent] is carried so subscribers can correlate close reason with
     * the action that fired.
     */
    public data class ButtonClick(public val intent: GuiIntent) : DialogCloseReason

    /**
     * Server-side `IDialogGateway.closeDialog()` was called (e.g. match
     * cleanup, admin override). No player interaction.
     */
    public data object ForceClose : DialogCloseReason

    /**
     * Player disconnected with the dialog still open. Wave 6b's bridge
     * adapter publishes this on `PlayerQuitEvent`.
     */
    public data object PlayerQuit : DialogCloseReason
}
