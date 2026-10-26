package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.dialog.DialogId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A [com.astarworks.astera.core.domain.dialog.DialogSpec] was successfully
 * pushed to a player's client (Wave 5i, ADR-0096).
 *
 * Published by `IDialogGateway.showDialog` implementations
 * ([com.astarworks.astera.core.application.service.InMemoryDialogGateway],
 * Wave 6b's `PaperDialogGateway`, and test-fixtures' `FakeDialogGateway`)
 * on dispatch success — does NOT imply the player has interacted yet.
 *
 * Initial subscribers: audit logger (Wave 5i+ tutorial / shop event trail)
 * and analytics. Real "what did the player do" signal arrives via
 * [DialogResponseReceived] and [DialogClosed].
 */
public data class DialogShown(
    val playerId: PlayerId,
    val dialogId: DialogId,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
