package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.perviewer.gui.IPerViewerGuiStateGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.perviewer.GuiHandle
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Cross-plugin Fake for [IPerViewerGuiStateGateway] (Wave 5y-ext, ADR-0112).
 * See [FakePerViewerNpcStateGateway] for the design rationale; same shape.
 *
 * Unlike Npc / Block fakes, GUI handles encode the owner viewer themselves
 * — the fake rejects handle-viewer mismatch with [UnknownTarget] without
 * needing an explicit `rejectHandle` helper.
 */
public class FakePerViewerGuiStateGateway : IPerViewerGuiStateGateway {

    public sealed interface Call {
        public val viewer: PlayerId
        public val handle: GuiHandle

        public data class OverrideSlotContent(
            override val viewer: PlayerId,
            override val handle: GuiHandle,
            val slotIndex: SlotIndex,
            val content: GuiSlot,
        ) : Call

        public data class OverrideTitle(
            override val viewer: PlayerId,
            override val handle: GuiHandle,
            val title: MessageKey,
        ) : Call

        public data class ClearOverridesForViewer(
            override val viewer: PlayerId,
            override val handle: GuiHandle,
        ) : Call
    }

    public val calls: MutableList<Call> = mutableListOf()
    private var backendFailure: String? = null
    private val overridesByHandle: MutableMap<GuiHandle, MutableMap<SlotIndex, GuiSlot>> = HashMap()
    private val titleByHandle: MutableMap<GuiHandle, MessageKey> = HashMap()

    public fun failBackend(reason: String?): Unit { backendFailure = reason }

    override fun overrideSlotContent(
        viewer: PlayerId,
        handle: GuiHandle,
        slotIndex: SlotIndex,
        content: GuiSlot,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideSlotContent(viewer, handle, slotIndex, content)
        backendFailure?.let { return Result.failure(PerViewerOverrideError.BackendError(it)) }
        if (handle.viewer != viewer) return Result.failure(PerViewerOverrideError.UnknownTarget)
        overridesByHandle.getOrPut(handle) { HashMap() }[slotIndex] = content
        return Result.success(Unit)
    }

    override fun overrideTitle(
        viewer: PlayerId,
        handle: GuiHandle,
        title: MessageKey,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideTitle(viewer, handle, title)
        backendFailure?.let { return Result.failure(PerViewerOverrideError.BackendError(it)) }
        if (handle.viewer != viewer) return Result.failure(PerViewerOverrideError.UnknownTarget)
        titleByHandle[handle] = title
        return Result.success(Unit)
    }

    override fun clearOverridesForViewer(viewer: PlayerId, handle: GuiHandle): Int {
        calls += Call.ClearOverridesForViewer(viewer, handle)
        if (handle.viewer != viewer) return 0
        val slots = overridesByHandle.remove(handle)?.size ?: 0
        val titleCount = if (titleByHandle.remove(handle) != null) 1 else 0
        return slots + titleCount
    }

    override fun clearAllOverridesForViewer(viewer: PlayerId): Int {
        val handles = overridesByHandle.keys.filter { it.viewer == viewer } +
            titleByHandle.keys.filter { it.viewer == viewer }
        val distinctHandles = handles.distinct()
        distinctHandles.forEach { h ->
            overridesByHandle.remove(h)
            titleByHandle.remove(h)
        }
        return distinctHandles.size
    }

    public fun clear() {
        calls.clear()
        backendFailure = null
        overridesByHandle.clear()
        titleByHandle.clear()
    }
}
