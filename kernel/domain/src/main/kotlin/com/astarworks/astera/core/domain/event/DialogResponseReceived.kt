package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.dialog.DialogId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A player clicked one of the dialog's buttons and the gateway has resolved
 * the response (Wave 5i, ADR-0096).
 *
 * Mirrors [com.astarworks.astera.core.domain.dialog.DialogResponse] but as a
 * cross-plugin domain event: subscribers (analytics, tutorial step machine,
 * shop confirmation handler) don't need to be the `onResponse` callback's
 * direct owner to react.
 *
 * Published alongside the per-call `onResponse` invocation
 * — both the direct callback AND any [com.astarworks.astera.core.application.port.outbound.IDomainEventBus]
 * subscribers fire.
 *
 * @property buttonIntent the [GuiIntent] of the button the player clicked.
 *   Same value flows through the direct callback's
 *   [com.astarworks.astera.core.domain.dialog.DialogResponse.triggeredButtonIntent].
 * @property inputValues stringified form values keyed by
 *   [com.astarworks.astera.core.domain.dialog.DialogInput.key]. May be empty
 *   if the dialog had no input fields.
 */
public data class DialogResponseReceived(
    val playerId: PlayerId,
    val dialogId: DialogId,
    val buttonIntent: GuiIntent,
    val inputValues: Map<String, String>,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
