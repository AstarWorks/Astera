package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.DialogError
import com.astarworks.astera.core.application.port.outbound.IDialogGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogAfterAction
import com.astarworks.astera.core.domain.dialog.DialogCloseReason
import com.astarworks.astera.core.domain.dialog.DialogId
import com.astarworks.astera.core.domain.dialog.DialogResponse
import com.astarworks.astera.core.domain.dialog.DialogSpec
import com.astarworks.astera.core.domain.event.DialogClosed
import com.astarworks.astera.core.domain.event.DialogResponseReceived
import com.astarworks.astera.core.domain.event.DialogShown
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IDialogGateway] for use case tests (Wave 5i, ADR-0096).
 *
 * Unlike [com.astarworks.astera.core.application.service.InMemoryDialogGateway]
 * (production no-op default that only publishes domain events), this fake
 * keeps the per-call `(playerId, spec, onResponse)` tuple so tests can drive
 * a synthetic player response and verify both the direct callback wiring
 * and the cross-plugin event chain.
 *
 * Typical test flow:
 * ```
 * val bus = FakeDomainEventBus()
 * val gateway = FakeDialogGateway(eventBus = bus)
 * useCase.run(gateway)                                  // calls showDialog
 * gateway.simulateResponse(playerId, dialogId, buttonIndex = 0,
 *     inputs = mapOf("name" to "Alice"))               // pretend player clicked
 * assertThat(bus.publishedOfType<DialogResponseReceived>()).hasSize(1)
 * ```
 *
 * [eventBus] is nullable so tests that don't care about the event surface
 * can construct `FakeDialogGateway()` with no arguments.
 */
public class FakeDialogGateway(
    private val eventBus: IDomainEventBus? = null,
) : IDialogGateway {

    /**
     * Snapshot of a single `showDialog` invocation kept for assertions and
     * later [simulateResponse] / [simulateForceClose] callbacks.
     */
    public data class ShownDialog(
        public val playerId: PlayerId,
        public val spec: DialogSpec,
        public val onResponse: ((DialogResponse) -> Unit)?,
    )

    /** History of every [showDialog] call, in order. Never pruned — even
     * dialogs that have been answered or closed remain here for assertions. */
    public val showCalls: MutableList<ShownDialog> = mutableListOf()

    /** History of every [closeDialog] call, in order. */
    public val closeCalls: MutableList<PlayerId> = mutableListOf()

    /**
     * Identity-keyed set of [showCalls] entries that have been answered
     * (via [simulateResponse]) or force-closed (via [simulateForceClose]
     * or [closeDialog]). A dialog stops being "open" the moment it lands
     * in this set; subsequent [simulateResponse] calls for the same id
     * will not find it.
     */
    private val resolved: MutableSet<ShownDialog> = mutableSetOf()

    override fun showDialog(
        playerId: PlayerId,
        spec: DialogSpec,
        onResponse: ((DialogResponse) -> Unit)?,
    ): Result<Unit, DialogError> {
        showCalls += ShownDialog(playerId, spec, onResponse)
        eventBus?.publish(DialogShown(playerId = playerId, dialogId = spec.id))
        return Result.success(Unit)
    }

    override fun closeDialog(playerId: PlayerId): Result<Unit, DialogError> {
        closeCalls += playerId
        // Any dialogs currently open for this player are force-closed.
        val open = openDialogsFor(playerId)
        for (shown in open) {
            resolved += shown
            eventBus?.publish(
                DialogClosed(
                    playerId = playerId,
                    dialogId = shown.spec.id,
                    reason = DialogCloseReason.ForceClose,
                ),
            )
        }
        return Result.success(Unit)
    }

    /**
     * Simulate a player clicking `spec.buttons[buttonIndex]` on the most
     * recent `showDialog(playerId, spec)` whose `spec.id == dialogId`.
     *
     * Drives:
     *  1. the per-call `onResponse` callback (if provided);
     *  2. a [DialogResponseReceived] event on the bus (if [eventBus] was set);
     *  3. a [DialogClosed] event with reason
     *     [DialogCloseReason.ButtonClick] — UNLESS the spec's
     *     [DialogSpec.afterAction] is [DialogAfterAction.Nothing], matching
     *     the Paper afterAction contract.
     *
     * @throws IllegalStateException if no matching open dialog is found.
     * @throws IllegalArgumentException if [buttonIndex] is out of range.
     */
    public fun simulateResponse(
        playerId: PlayerId,
        dialogId: DialogId,
        buttonIndex: Int,
        inputs: Map<String, String> = emptyMap(),
    ) {
        val shown = openDialogsFor(playerId)
            .lastOrNull { it.spec.id == dialogId }
            ?: error(
                "No open dialog found for playerId=$playerId dialogId=$dialogId " +
                    "(showCalls=${showCalls.size}, closeCalls=${closeCalls.size})",
            )
        require(buttonIndex in shown.spec.buttons.indices) {
            "buttonIndex $buttonIndex out of range for spec with ${shown.spec.buttons.size} buttons"
        }
        val button = shown.spec.buttons[buttonIndex]
        val response = DialogResponse(
            playerId = playerId,
            dialogId = dialogId,
            triggeredButtonIntent = button.intent,
            inputValues = inputs,
        )

        shown.onResponse?.invoke(response)

        eventBus?.publish(
            DialogResponseReceived(
                playerId = playerId,
                dialogId = dialogId,
                buttonIntent = button.intent,
                inputValues = inputs,
            ),
        )
        if (shown.spec.afterAction != DialogAfterAction.Nothing) {
            resolved += shown
            eventBus?.publish(
                DialogClosed(
                    playerId = playerId,
                    dialogId = dialogId,
                    reason = DialogCloseReason.ButtonClick(button.intent),
                ),
            )
        }
        // afterAction == Nothing: dialog stays open, do not mark resolved.
    }

    /**
     * Simulate the gateway being force-closed for [playerId] (admin override,
     * match cleanup). Publishes [DialogClosed] with reason
     * [DialogCloseReason.ForceClose] for each currently-open dialog for the
     * player. Does NOT invoke any `onResponse` callbacks.
     *
     * @throws IllegalStateException if no dialog is open for [playerId].
     */
    public fun simulateForceClose(playerId: PlayerId) {
        val open = openDialogsFor(playerId)
        check(open.isNotEmpty()) {
            "simulateForceClose: no open dialog for playerId=$playerId"
        }
        for (shown in open) {
            resolved += shown
            eventBus?.publish(
                DialogClosed(
                    playerId = playerId,
                    dialogId = shown.spec.id,
                    reason = DialogCloseReason.ForceClose,
                ),
            )
        }
    }

    /**
     * Test cleanup hook — drops every recorded call. Useful in `@AfterEach`
     * when reusing a single gateway across tests.
     */
    public fun clear() {
        showCalls.clear()
        closeCalls.clear()
        resolved.clear()
    }

    /**
     * The dialogs currently considered "open" for [playerId]: every
     * [showDialog] call that has not yet been answered via [simulateResponse]
     * (with `afterAction != Nothing`), force-closed via [simulateForceClose],
     * or closed via [closeDialog].
     */
    private fun openDialogsFor(playerId: PlayerId): List<ShownDialog> =
        showCalls.filter { it.playerId == playerId && it !in resolved }
}
