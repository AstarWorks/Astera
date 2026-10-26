package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.dialog.DialogCloseReason
import com.astarworks.astera.core.domain.dialog.DialogId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A dialog disappeared from a player's screen (Wave 5i, ADR-0096).
 *
 * Publication timing depends on
 * [com.astarworks.astera.core.domain.dialog.DialogSpec.afterAction]:
 *  - `CloseDialog` (default) — fired immediately after the button-click
 *    callback returns.
 *  - `WaitForResponse` — fired when the handler explicitly confirms close
 *    (Wave 6b adapter wiring).
 *  - `Nothing` — fired only on [DialogCloseReason.ForceClose] or
 *    [DialogCloseReason.PlayerQuit]; button clicks do NOT close, so no
 *    `DialogClosed` for [DialogCloseReason.ButtonClick] in that mode.
 *
 * Pairs with [DialogShown] (open) and [DialogResponseReceived] (interaction).
 * Subscribers wanting the full lifecycle should join all three.
 */
public data class DialogClosed(
    val playerId: PlayerId,
    val dialogId: DialogId,
    val reason: DialogCloseReason,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
