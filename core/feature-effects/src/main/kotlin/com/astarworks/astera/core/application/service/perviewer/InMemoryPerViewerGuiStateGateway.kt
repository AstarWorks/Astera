package com.astarworks.astera.core.application.service.perviewer

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.perviewer.gui.IPerViewerGuiStateGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.GuiOverrideApplied
import com.astarworks.astera.core.domain.event.PerViewerOverridesCleared
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.perviewer.ClearReason
import com.astarworks.astera.core.domain.perviewer.GuiHandle
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory default [IPerViewerGuiStateGateway] (Wave 5y-ext, ADR-0112).
 *
 * Per-handle override map: `viewer → handle → (slotOverrides, titleOverride)`.
 * The handle's owner-check is enforced on every mutator call — applying
 * viewer A's overrides through viewer B's handle returns [UnknownTarget].
 *
 * No registration step required (unlike Npc / Block gateways): the handle
 * itself encodes both viewer and GUI id, so the gateway treats every
 * presented handle as valid as long as `handle.viewer == viewer`. The
 * platform-side `IGuiAdapter` impl is the sole minter and lifecycle owner
 * for [GuiHandle] values; the Wave 6 Paper adapter cleanup calls
 * [clearOverridesForViewer] on close to drop residual overrides.
 *
 * Per ADR-0112 the gateway is **not** thread-safe.
 */
public class InMemoryPerViewerGuiStateGateway(
    private val eventBus: IDomainEventBus? = null,
) : IPerViewerGuiStateGateway {

    private data class HandleState(
        val slots: MutableMap<SlotIndex, GuiSlot> = mutableMapOf(),
        var title: MessageKey? = null,
    ) {
        fun isEmpty(): Boolean = slots.isEmpty() && title == null
        fun size(): Int = slots.size + (if (title != null) 1 else 0)
    }

    /** viewer → handle → state */
    private val overridesByViewer: MutableMap<PlayerId, MutableMap<GuiHandle, HandleState>> = HashMap()

    override fun overrideSlotContent(
        viewer: PlayerId,
        handle: GuiHandle,
        slotIndex: SlotIndex,
        content: GuiSlot,
    ): Result<Unit, PerViewerOverrideError> {
        if (handle.viewer != viewer) return Result.failure(PerViewerOverrideError.UnknownTarget)
        val state = handleState(viewer, handle)
        val existing = state.slots[slotIndex]
        if (existing == content) return Result.success(Unit)
        state.slots[slotIndex] = content
        eventBus?.publish(
            GuiOverrideApplied(
                handle = handle,
                slotIndex = slotIndex,
                slotOverride = content,
            ),
        )
        return Result.success(Unit)
    }

    override fun overrideTitle(
        viewer: PlayerId,
        handle: GuiHandle,
        title: MessageKey,
    ): Result<Unit, PerViewerOverrideError> {
        if (handle.viewer != viewer) return Result.failure(PerViewerOverrideError.UnknownTarget)
        val state = handleState(viewer, handle)
        if (state.title == title) return Result.success(Unit)
        state.title = title
        eventBus?.publish(
            GuiOverrideApplied(
                handle = handle,
                titleOverride = title,
            ),
        )
        return Result.success(Unit)
    }

    override fun clearOverridesForViewer(
        viewer: PlayerId,
        handle: GuiHandle,
    ): Int {
        if (handle.viewer != viewer) return 0
        val bucket = overridesByViewer[viewer] ?: return 0
        val state = bucket.remove(handle) ?: return 0
        if (bucket.isEmpty()) overridesByViewer.remove(viewer)
        val count = state.size()
        if (count > 0) {
            eventBus?.publish(
                PerViewerOverridesCleared(
                    viewer = viewer,
                    gatewayFamily = PerViewerOverridesCleared.FAMILY_GUI,
                    reason = ClearReason.Explicit,
                    clearedCount = count,
                ),
            )
        }
        return count
    }

    /**
     * Sweep every GUI handle's overrides for [viewer]. Returns the number
     * of distinct handles that held at least one override (matches the
     * port contract docstring).
     *
     * Public callers (use-case `clearForReset()` flows) get
     * [ClearReason.Explicit]; the internal `PlayerQuitServer` subscriber
     * should call [sweepOnQuit] instead.
     */
    override fun clearAllOverridesForViewer(viewer: PlayerId): Int =
        sweep(viewer, ClearReason.Explicit)

    /**
     * Variant of [clearAllOverridesForViewer] used by the gateway's
     * internal `PlayerQuitServer` subscriber so the emitted
     * [PerViewerOverridesCleared] carries [ClearReason.PlayerQuit]. Kept
     * as a separate entry-point (rather than a flag on the override) to
     * avoid polluting the port surface with subscriber-internal concerns.
     */
    public fun sweepOnQuit(viewer: PlayerId): Int =
        sweep(viewer, ClearReason.PlayerQuit)

    private fun sweep(viewer: PlayerId, reason: ClearReason): Int {
        val bucket = overridesByViewer.remove(viewer) ?: return 0
        val handleCount = bucket.size
        if (handleCount > 0) {
            eventBus?.publish(
                PerViewerOverridesCleared(
                    viewer = viewer,
                    gatewayFamily = PerViewerOverridesCleared.FAMILY_GUI,
                    reason = reason,
                    clearedCount = handleCount,
                ),
            )
        }
        return handleCount
    }

    private fun handleState(viewer: PlayerId, handle: GuiHandle): HandleState =
        overridesByViewer
            .getOrPut(viewer) { HashMap() }
            .getOrPut(handle) { HandleState() }
}
