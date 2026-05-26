package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.perviewer.npc.IPerViewerNpcStateGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcSkin
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.perviewer.NpcAttributeKind
import com.astarworks.astera.core.domain.perviewer.NpcViewerOverride
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Cross-plugin Fake for [IPerViewerNpcStateGateway] (Wave 5y-ext, ADR-0112).
 *
 * Differs from `InMemoryPerViewerNpcStateGateway` in three ways:
 *  1. No domain events are published — tests that care about events should
 *     wire the in-memory variant directly with [FakeDomainEventBus].
 *  2. No viewer / target tracking required — every call accepted by default.
 *     Use [rejectViewer] / [rejectNpc] / [failBackend] to simulate failure
 *     modes per [PerViewerOverrideError].
 *  3. Recorded call lists let tests assert "the use case called
 *     `overrideSkin` in this order" without instantiating Paper.
 */
public class FakePerViewerNpcStateGateway : IPerViewerNpcStateGateway {

    public sealed interface Call {
        public val viewer: PlayerId
        public val npc: EntityHandle

        public data class OverrideSkin(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
            val skin: NpcSkin,
        ) : Call

        public data class ResetSkin(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
        ) : Call

        public data class OverrideName(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
            val displayName: MessageKey,
        ) : Call

        public data class ResetName(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
        ) : Call

        public data class OverrideEquipment(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
            val slot: EquipSlot,
            val item: ItemId?,
        ) : Call

        public data class ResetEquipment(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
        ) : Call

        public data class OverrideAttribute(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
            val attribute: NpcAttributeKind,
        ) : Call

        public data class ClearAllOverrides(
            override val viewer: PlayerId,
            override val npc: EntityHandle,
        ) : Call
    }

    public val calls: MutableList<Call> = mutableListOf()

    private val rejectedViewers: MutableSet<PlayerId> = HashSet()
    private val rejectedNpcs: MutableSet<EntityHandle> = HashSet()
    private var backendFailure: String? = null

    private val snapshotsByViewer: MutableMap<PlayerId, MutableMap<EntityHandle, NpcViewerOverride>> = HashMap()

    public fun rejectViewer(viewer: PlayerId): Unit { rejectedViewers += viewer }
    public fun rejectNpc(npc: EntityHandle): Unit { rejectedNpcs += npc }
    public fun failBackend(reason: String?): Unit { backendFailure = reason }

    override fun overrideSkin(
        viewer: PlayerId,
        npc: EntityHandle,
        skin: NpcSkin,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideSkin(viewer, npc, skin)
        return apply(viewer, npc) { it.copy(skin = skin) }
    }

    override fun resetSkin(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.ResetSkin(viewer, npc)
        return apply(viewer, npc) { it.copy(skin = null) }
    }

    override fun overrideName(
        viewer: PlayerId,
        npc: EntityHandle,
        displayName: MessageKey,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideName(viewer, npc, displayName)
        return apply(viewer, npc) { it.copy(displayName = displayName) }
    }

    override fun resetName(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.ResetName(viewer, npc)
        return apply(viewer, npc) { it.copy(displayName = null) }
    }

    override fun overrideEquipment(
        viewer: PlayerId,
        npc: EntityHandle,
        slot: EquipSlot,
        item: ItemId?,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideEquipment(viewer, npc, slot, item)
        return apply(viewer, npc) { it.copy(equipment = it.equipment + (slot to item)) }
    }

    override fun resetEquipment(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.ResetEquipment(viewer, npc)
        return apply(viewer, npc) { it.copy(equipment = emptyMap()) }
    }

    override fun overrideAttribute(
        viewer: PlayerId,
        npc: EntityHandle,
        attribute: NpcAttributeKind,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideAttribute(viewer, npc, attribute)
        val key = attribute::class.simpleName.orEmpty()
        return apply(viewer, npc) { it.copy(attributes = it.attributes + (key to attribute)) }
    }

    override fun clearAllOverrides(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.ClearAllOverrides(viewer, npc)
        backendFailure?.let { return Result.failure(PerViewerOverrideError.BackendError(it)) }
        if (viewer in rejectedViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        if (npc in rejectedNpcs) return Result.failure(PerViewerOverrideError.UnknownTarget)
        snapshotsByViewer[viewer]?.remove(npc)
        return Result.success(Unit)
    }

    override fun clearAllOverridesForViewer(viewer: PlayerId): Int {
        val cleared = snapshotsByViewer.remove(viewer)?.size ?: 0
        return cleared
    }

    override fun currentOverrides(
        viewer: PlayerId,
        npc: EntityHandle,
    ): NpcViewerOverride? = snapshotsByViewer[viewer]?.get(npc)?.takeUnless { it.isEmpty }

    public fun clear() {
        calls.clear()
        rejectedViewers.clear()
        rejectedNpcs.clear()
        backendFailure = null
        snapshotsByViewer.clear()
    }

    private inline fun apply(
        viewer: PlayerId,
        npc: EntityHandle,
        transform: (NpcViewerOverride) -> NpcViewerOverride,
    ): Result<Unit, PerViewerOverrideError> {
        backendFailure?.let { return Result.failure(PerViewerOverrideError.BackendError(it)) }
        if (viewer in rejectedViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        if (npc in rejectedNpcs) return Result.failure(PerViewerOverrideError.UnknownTarget)
        val bucket = snapshotsByViewer.getOrPut(viewer) { HashMap() }
        bucket[npc] = transform(bucket[npc] ?: NpcViewerOverride())
        return Result.success(Unit)
    }
}
