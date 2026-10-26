package com.astarworks.astera.core.application.service.perviewer

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.perviewer.npc.IPerViewerNpcStateGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcSkin
import com.astarworks.astera.core.domain.event.NpcOverrideApplied
import com.astarworks.astera.core.domain.event.PerViewerOverridesCleared
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.perviewer.ClearReason
import com.astarworks.astera.core.domain.perviewer.NpcAttributeKind
import com.astarworks.astera.core.domain.perviewer.NpcViewerOverride
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID

/**
 * In-memory default [IPerViewerNpcStateGateway] (Wave 5y-ext, ADR-0112).
 *
 * Two-tier index:
 *  - viewer → (npcUuid → [NpcViewerOverride])
 *  - npcUuid → tracking metadata (currently just the [EntityHandle] for
 *    read-back; future waves may attach packet-state cache)
 *
 * The Phase 1 logic-only impl never fails to apply an override; the only
 * error path is [PerViewerOverrideError.UnknownViewer] / [UnknownTarget]
 * gated by [trackViewer] / [trackEntity]. Mutator-side equality detection
 * suppresses the [NpcOverrideApplied] event when re-applying the same
 * payload, matching the port contract.
 *
 * Per ADR-0112 the gateway is **not** thread-safe (Paper main thread only).
 */
public class InMemoryPerViewerNpcStateGateway(
    private val eventBus: IDomainEventBus? = null,
) : IPerViewerNpcStateGateway {

    private val knownViewers: MutableSet<PlayerId> = HashSet()
    private val knownNpcs: MutableMap<UUID, EntityHandle> = HashMap()

    /** viewer → npcUuid → current override snapshot */
    private val overridesByViewer: MutableMap<PlayerId, MutableMap<UUID, NpcViewerOverride>> = HashMap()

    /**
     * Mark [viewer] as a viable target for override calls. Typically called
     * on `PlayerJoinedServer`; in-memory mode requires tests to register
     * explicitly.
     */
    public fun trackViewer(viewer: PlayerId) {
        knownViewers += viewer
    }

    /** Stop tracking [viewer]; drops every staged override for them and emits one cleared event. */
    public fun untrackViewer(viewer: PlayerId) {
        knownViewers -= viewer
        sweep(viewer, ClearReason.PlayerQuit)
    }

    /** Mark [npc] as a viable override target (typically called when NPC spawns). */
    public fun trackEntity(npc: EntityHandle) {
        knownNpcs[npc.id] = npc
    }

    /**
     * Stop tracking [npc]; sweeps every viewer's overrides for that NPC and
     * emits one cleared event per affected viewer.
     */
    public fun untrackEntity(npc: EntityHandle) {
        knownNpcs.remove(npc.id)
        val affected = mutableMapOf<PlayerId, Int>()
        overridesByViewer.forEach { (viewer, bucket) ->
            if (bucket.remove(npc.id) != null) {
                affected[viewer] = (affected[viewer] ?: 0) + 1
            }
        }
        affected.forEach { (viewer, count) ->
            eventBus?.publish(
                PerViewerOverridesCleared(
                    viewer = viewer,
                    gatewayFamily = PerViewerOverridesCleared.FAMILY_NPC,
                    reason = ClearReason.TargetDespawned,
                    clearedCount = count,
                ),
            )
        }
    }

    override fun overrideSkin(
        viewer: PlayerId,
        npc: EntityHandle,
        skin: NpcSkin,
    ): Result<Unit, PerViewerOverrideError> = mutate(viewer, npc) { current ->
        if (current.skin == skin) current else current.copy(skin = skin)
    }

    override fun resetSkin(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> = mutate(viewer, npc) { current ->
        if (current.skin == null) current else current.copy(skin = null)
    }

    override fun overrideName(
        viewer: PlayerId,
        npc: EntityHandle,
        displayName: MessageKey,
    ): Result<Unit, PerViewerOverrideError> = mutate(viewer, npc) { current ->
        if (current.displayName == displayName) current else current.copy(displayName = displayName)
    }

    override fun resetName(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> = mutate(viewer, npc) { current ->
        if (current.displayName == null) current else current.copy(displayName = null)
    }

    override fun overrideEquipment(
        viewer: PlayerId,
        npc: EntityHandle,
        slot: EquipSlot,
        item: ItemId?,
    ): Result<Unit, PerViewerOverrideError> = mutate(viewer, npc) { current ->
        // A null item with no existing entry → no-op (caller likely wants to
        // hide-default which requires explicit map-put with null value).
        // To distinguish, we always put the entry on override; reset-equipment
        // is what removes the keys entirely.
        val existing = current.equipment
        if (existing.containsKey(slot) && existing[slot] == item) {
            current
        } else {
            current.copy(equipment = existing + (slot to item))
        }
    }

    override fun resetEquipment(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> = mutate(viewer, npc) { current ->
        if (current.equipment.isEmpty()) current else current.copy(equipment = emptyMap())
    }

    override fun overrideAttribute(
        viewer: PlayerId,
        npc: EntityHandle,
        attribute: NpcAttributeKind,
    ): Result<Unit, PerViewerOverrideError> = mutate(viewer, npc) { current ->
        val key = attributeKey(attribute)
        if (current.attributes[key] == attribute) {
            current
        } else {
            current.copy(attributes = current.attributes + (key to attribute))
        }
    }

    override fun clearAllOverrides(
        viewer: PlayerId,
        npc: EntityHandle,
    ): Result<Unit, PerViewerOverrideError> {
        if (viewer !in knownViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        if (npc.id !in knownNpcs) return Result.failure(PerViewerOverrideError.UnknownTarget)
        val bucket = overridesByViewer[viewer] ?: return Result.success(Unit)
        val removed = bucket.remove(npc.id)
        if (bucket.isEmpty()) overridesByViewer.remove(viewer)
        if (removed != null && !removed.isEmpty) {
            eventBus?.publish(
                PerViewerOverridesCleared(
                    viewer = viewer,
                    gatewayFamily = PerViewerOverridesCleared.FAMILY_NPC,
                    reason = ClearReason.Explicit,
                    clearedCount = 1,
                ),
            )
        }
        return Result.success(Unit)
    }

    override fun clearAllOverridesForViewer(viewer: PlayerId): Int =
        sweep(viewer, ClearReason.Explicit)

    override fun currentOverrides(
        viewer: PlayerId,
        npc: EntityHandle,
    ): NpcViewerOverride? = overridesByViewer[viewer]?.get(npc.id)?.takeUnless { it.isEmpty }

    /**
     * Common mutator path: validates viewer + npc, mutates the slot via
     * [transform], publishes [NpcOverrideApplied] iff the snapshot changed.
     */
    private inline fun mutate(
        viewer: PlayerId,
        npc: EntityHandle,
        transform: (NpcViewerOverride) -> NpcViewerOverride,
    ): Result<Unit, PerViewerOverrideError> {
        if (viewer !in knownViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        if (npc.id !in knownNpcs) return Result.failure(PerViewerOverrideError.UnknownTarget)
        val bucket = overridesByViewer.getOrPut(viewer) { HashMap() }
        val previous = bucket[npc.id] ?: NpcViewerOverride()
        val next = transform(previous)
        if (next === previous || next == previous) return Result.success(Unit)
        if (next.isEmpty) {
            bucket.remove(npc.id)
            if (bucket.isEmpty()) overridesByViewer.remove(viewer)
        } else {
            bucket[npc.id] = next
        }
        eventBus?.publish(NpcOverrideApplied(viewer = viewer, npc = npc, snapshot = next))
        return Result.success(Unit)
    }

    /**
     * Sweep every NPC-override slot for [viewer] (used by quit + explicit
     * mass clear). Returns the affected NPC count and emits one
     * [PerViewerOverridesCleared] when at least one slot was removed.
     */
    private fun sweep(viewer: PlayerId, reason: ClearReason): Int {
        val bucket = overridesByViewer.remove(viewer) ?: return 0
        val count = bucket.size
        if (count > 0) {
            eventBus?.publish(
                PerViewerOverridesCleared(
                    viewer = viewer,
                    gatewayFamily = PerViewerOverridesCleared.FAMILY_NPC,
                    reason = reason,
                    clearedCount = count,
                ),
            )
        }
        return count
    }

    private companion object {
        fun attributeKey(attribute: NpcAttributeKind): String = when (attribute) {
            is NpcAttributeKind.Scale -> "scale"
            is NpcAttributeKind.Glowing -> "glowing"
            is NpcAttributeKind.Invisible -> "invisible"
            is NpcAttributeKind.NameVisible -> "name_visible"
        }
    }
}
