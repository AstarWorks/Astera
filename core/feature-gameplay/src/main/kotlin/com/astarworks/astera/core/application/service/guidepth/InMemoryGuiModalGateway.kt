package com.astarworks.astera.core.application.service.guidepth

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.guidepth.GuiModalError
import com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ModalDismissed
import com.astarworks.astera.core.domain.event.ModalShown
import com.astarworks.astera.core.domain.guidepth.GuiModalSpec
import com.astarworks.astera.core.domain.guidepth.ModalHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default in-memory [IGuiModalGateway] (Wave 5gui-depth, ADR-0114).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6 lands
 * `PaperGuiModalGateway` over the anvil-prompt + boss-bar + custom overlay
 * packet stack at higher priority and shadows this impl.
 *
 * **Not thread-safe** beyond what [ConcurrentHashMap] / [CopyOnWriteArrayList]
 * provide. The Paper adapter pipeline runs on the main thread; the concurrent
 * structures are precautionary for cross-thread packet handlers in Wave 6.
 *
 * # Scheduler-free by design
 *
 * `Notification.dismissAfterTicks` auto-dismiss is **not** scheduled here
 * (no `IScheduler` dependency — mirrors the Wave 5cinematic
 * `InMemoryCameraGateway.freeze` decision). Tests dismiss explicitly; the
 * Paper adapter (Wave 6) wires `IScheduler.run(dismissAfterTicks) {
 * dismissModal(handle) }`.
 */
public class InMemoryGuiModalGateway(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IGuiModalGateway {

    private data class ActiveModal(val playerId: PlayerId, val handle: ModalHandle)

    private val byHandle: ConcurrentHashMap<ModalHandle, PlayerId> = ConcurrentHashMap()
    private val byPlayer: ConcurrentHashMap<PlayerId, CopyOnWriteArrayList<ModalHandle>> =
        ConcurrentHashMap()

    override fun showModal(
        playerId: PlayerId,
        modal: GuiModalSpec,
    ): Result<ModalHandle, GuiModalError> {
        val handle = mintHandle()
        byHandle[handle] = playerId
        byPlayer.computeIfAbsent(playerId) { CopyOnWriteArrayList() }.add(handle)
        eventBus.publish(
            ModalShown(
                playerId = playerId,
                handle = handle,
                modalKind = kindOf(modal),
                atTick = tickClock(),
            ),
        )
        return Result.success(handle)
    }

    override fun dismissModal(handle: ModalHandle): Result<Unit, GuiModalError> {
        val playerId = byHandle.remove(handle) ?: return Result.success(Unit)
        byPlayer[playerId]?.remove(handle)
        eventBus.publish(
            ModalDismissed(
                playerId = playerId,
                handle = handle,
                reasonKind = "explicit",
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun dismissAllForPlayer(playerId: PlayerId): Result<Unit, GuiModalError> {
        val handles = byPlayer.remove(playerId) ?: return Result.success(Unit)
        for (handle in handles) {
            byHandle.remove(handle)
            eventBus.publish(
                ModalDismissed(
                    playerId = playerId,
                    handle = handle,
                    reasonKind = "bulk_dismiss",
                    atTick = tickClock(),
                ),
            )
        }
        return Result.success(Unit)
    }

    override fun activeModals(playerId: PlayerId): List<ModalHandle> =
        byPlayer[playerId]?.toList().orEmpty()

    /**
     * Test helper — simulate the Paper-side auto-dismiss of a
     * `Notification.dismissAfterTicks` window. Fires
     * [ModalDismissed] with `reasonKind = "auto_expired"` and removes the
     * handle. Idempotent: dismissing an unknown handle is a no-op.
     */
    public fun simulateAutoExpire(handle: ModalHandle) {
        val playerId = byHandle.remove(handle) ?: return
        byPlayer[playerId]?.remove(handle)
        eventBus.publish(
            ModalDismissed(
                playerId = playerId,
                handle = handle,
                reasonKind = "auto_expired",
                atTick = tickClock(),
            ),
        )
    }

    private fun kindOf(modal: GuiModalSpec): String = when (modal) {
        is GuiModalSpec.Confirmation -> "confirmation"
        is GuiModalSpec.Notification -> "notification"
        is GuiModalSpec.QuickbarSelector -> "quickbar_selector"
        is GuiModalSpec.TooltipOverlay -> "tooltip_overlay"
        is GuiModalSpec.Custom -> "custom"
    }

    private fun mintHandle(): ModalHandle = ModalHandle(UUID.randomUUID().toString())
}
