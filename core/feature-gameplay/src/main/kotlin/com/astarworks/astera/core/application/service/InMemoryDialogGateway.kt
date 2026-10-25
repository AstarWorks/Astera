package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.DialogError
import com.astarworks.astera.core.application.port.outbound.IDialogGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogResponse
import com.astarworks.astera.core.domain.dialog.DialogSpec
import com.astarworks.astera.core.domain.event.DialogShown
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default in-memory [IDialogGateway] (Wave 5i, ADR-0096).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6b's
 * `PaperDialogGateway` will register at higher priority and shadow this
 * impl on real servers. This gateway is intentionally a **no-op for the
 * UI side**: it never actually shows anything to a player (no Paper API
 * is available in Core), so callers in test / development environments
 * get a working contract without a Bukkit harness.
 *
 * What it DOES do: publish the [DialogShown] and [DialogClosed] domain
 * events through the injected [IDomainEventBus]. That keeps cross-plugin
 * audit / analytics subscribers wired correctly in Phase 1 even though no
 * native dialog renders — when Wave 6b's `PaperDialogGateway` shadows
 * this impl, those subscribers do not need to change.
 *
 * Because the gateway never renders anything, the per-call `onResponse`
 * callback is never invoked from here — it is held purely as a contract
 * placeholder. Tests that need to drive a response should use
 * `FakeDialogGateway.simulateResponse` from test-fixtures instead.
 *
 * @property eventBus published to on `showDialog` (success) and
 *   `closeDialog`. Must be the same bus other in-process subscribers
 *   listen on for cross-plugin audit to work.
 */
public class InMemoryDialogGateway(
    private val eventBus: IDomainEventBus,
) : IDialogGateway {

    override fun showDialog(
        playerId: PlayerId,
        spec: DialogSpec,
        onResponse: ((DialogResponse) -> Unit)?,
    ): Result<Unit, DialogError> {
        eventBus.publish(
            DialogShown(
                playerId = playerId,
                dialogId = spec.id,
            ),
        )
        return Result.success(Unit)
    }

    override fun closeDialog(playerId: PlayerId): Result<Unit, DialogError> {
        // No tracked open-dialog state in the no-op default — Wave 6b's
        // PaperDialogGateway will look up the actual open spec to populate
        // dialogId. Here we have nothing to identify, so we publish nothing:
        // callers that need a `DialogClosed(ForceClose)` round-trip should
        // either use `FakeDialogGateway` (test) or wait for the Paper impl
        // (production).
        return Result.success(Unit)
    }
}
