package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogResponse
import com.astarworks.astera.core.domain.dialog.DialogSpec
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: server-driven native Minecraft Dialog
 * (Wave 5i, ADR-0096).
 *
 * Unlike [IGuiAdapter] (inventory-based, persistent, reactive), a Dialog is
 * a promise-like 1-shot interaction — the player sees a native UI overlay
 * with form inputs + buttons, picks a button, and the response arrives
 * once. The dialog auto-closes on response (per [DialogSpec.afterAction]).
 *
 * Wave 5i ships `InMemoryDialogGateway` (priority 0, no-op default) +
 * `FakeDialogGateway` (test-fixtures, with `simulateResponse()` test
 * helper). Wave 6b lands `PaperDialogGateway` over `Player.showDialog()` +
 * `DialogActionCallback`.
 */
public interface IDialogGateway {

    /**
     * Show [spec] to [playerId]. Renders client-side as a native Minecraft
     * dialog overlay (Paper API). On player response (button click), the
     * gateway:
     *  1. invokes [onResponse] callback (if provided) for direct continuation
     *  2. publishes a `DialogResponseReceived` domain event for cross-plugin
     *     subscribers (audit, analytics)
     *  3. closes the dialog (per [DialogSpec.afterAction])
     *
     * Returns success on dispatch (does NOT wait for response).
     */
    public fun showDialog(
        playerId: PlayerId,
        spec: DialogSpec,
        onResponse: ((DialogResponse) -> Unit)? = null,
    ): Result<Unit, DialogError>

    /** Force-close any dialog currently shown to [playerId]. Idempotent. */
    public fun closeDialog(playerId: PlayerId): Result<Unit, DialogError>
}

public sealed class DialogError {
    public data class UnknownPlayer(public val playerId: PlayerId) : DialogError()
    public data class InvalidSpec(public val detail: String) : DialogError()
    public data class Storage(public val detail: String) : DialogError()
}
