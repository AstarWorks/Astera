package com.astarworks.astera.core.application.service.perviewer

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.perviewer.block.IPerViewerBlockStateGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.BlockOverrideApplied
import com.astarworks.astera.core.domain.event.PerViewerOverridesCleared
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.perviewer.ClearReason
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * In-memory default [IPerViewerBlockStateGateway] (Wave 5y-ext, ADR-0112).
 *
 * Per-viewer index keyed by `(WorldId, Vec3)` pair (positions are stored as
 * value-class triples so two `.equals()` calls on the same coordinate cap
 * cleanly). Each entry holds the active override axis tuple — at most one
 * axis is active per entry due to the [BlockOverrideApplied] init guard:
 * applying a new axis displaces the previous.
 *
 * Per ADR-0112 the gateway is **not** thread-safe.
 */
public class InMemoryPerViewerBlockStateGateway(
    private val eventBus: IDomainEventBus? = null,
) : IPerViewerBlockStateGateway {

    private val knownViewers: MutableSet<PlayerId> = HashSet()

    private data class Key(val world: WorldId, val position: Vec3)

    private data class Entry(
        val overrideType: BlockType? = null,
        val overrideTexture: HudGlyphRef? = null,
        val hidden: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = overrideType == null && overrideTexture == null && !hidden
    }

    /** viewer → key → entry */
    private val overridesByViewer: MutableMap<PlayerId, MutableMap<Key, Entry>> = HashMap()

    public fun trackViewer(viewer: PlayerId) {
        knownViewers += viewer
    }

    public fun untrackViewer(viewer: PlayerId) {
        knownViewers -= viewer
        sweep(viewer, ClearReason.PlayerQuit)
    }

    override fun overrideBlockType(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
        blockType: BlockType,
    ): Result<Unit, PerViewerOverrideError> {
        if (viewer !in knownViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        val mutated = mutate(viewer, world, position) {
            Entry(overrideType = blockType)
        } ?: return Result.success(Unit)
        eventBus?.publish(
            BlockOverrideApplied(
                viewer = viewer,
                world = world,
                position = position,
                overrideType = mutated.overrideType,
            ),
        )
        return Result.success(Unit)
    }

    override fun overrideTexture(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
        textureRef: HudGlyphRef,
    ): Result<Unit, PerViewerOverrideError> {
        if (viewer !in knownViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        val mutated = mutate(viewer, world, position) {
            Entry(overrideTexture = textureRef)
        } ?: return Result.success(Unit)
        eventBus?.publish(
            BlockOverrideApplied(
                viewer = viewer,
                world = world,
                position = position,
                overrideTexture = mutated.overrideTexture,
            ),
        )
        return Result.success(Unit)
    }

    override fun hideBlock(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError> {
        if (viewer !in knownViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        val bucket = overridesByViewer.getOrPut(viewer) { HashMap() }
        val key = Key(world, position)
        val previous = bucket[key]
        if (previous?.hidden == true) return Result.success(Unit)
        // Keep any type / texture so a future showBlock restores them, but
        // mark hidden=true; the event reports only the changed axis per
        // BlockOverrideApplied invariant — so we publish a hidden=true
        // event with no type / texture in the payload.
        bucket[key] = (previous ?: Entry()).copy(hidden = true)
        eventBus?.publish(
            BlockOverrideApplied(
                viewer = viewer,
                world = world,
                position = position,
                hidden = true,
            ),
        )
        return Result.success(Unit)
    }

    override fun showBlock(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError> {
        if (viewer !in knownViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        val bucket = overridesByViewer[viewer] ?: return Result.success(Unit)
        val key = Key(world, position)
        val previous = bucket[key] ?: return Result.success(Unit)
        if (!previous.hidden) return Result.success(Unit)
        val next = previous.copy(hidden = false)
        if (next.isEmpty) {
            bucket.remove(key)
            if (bucket.isEmpty()) overridesByViewer.remove(viewer)
        } else {
            bucket[key] = next
            // Publish the now-restored axis so subscribers know to re-render
            // — pick whichever axis remains; type wins if both are set
            // (shouldn't happen by the discriminator invariant but safe).
            val restoredEvent = when {
                next.overrideType != null -> BlockOverrideApplied(
                    viewer = viewer,
                    world = world,
                    position = position,
                    overrideType = next.overrideType,
                )
                next.overrideTexture != null -> BlockOverrideApplied(
                    viewer = viewer,
                    world = world,
                    position = position,
                    overrideTexture = next.overrideTexture,
                )
                else -> null
            }
            restoredEvent?.let { eventBus?.publish(it) }
        }
        return Result.success(Unit)
    }

    override fun clearOverride(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError> {
        if (viewer !in knownViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        val bucket = overridesByViewer[viewer] ?: return Result.success(Unit)
        val key = Key(world, position)
        val removed = bucket.remove(key)
        if (bucket.isEmpty()) overridesByViewer.remove(viewer)
        if (removed != null && !removed.isEmpty) {
            eventBus?.publish(
                PerViewerOverridesCleared(
                    viewer = viewer,
                    gatewayFamily = PerViewerOverridesCleared.FAMILY_BLOCK,
                    reason = ClearReason.Explicit,
                    clearedCount = 1,
                ),
            )
        }
        return Result.success(Unit)
    }

    override fun clearAllOverridesForViewer(viewer: PlayerId): Int =
        sweep(viewer, ClearReason.Explicit)

    /**
     * Apply [transform] to the entry; if the new entry differs from the
     * previous, persist and return the new entry. Returns null on no-op
     * (callers report success without publishing event). Viewer-validation
     * happens at the call site so the no-op vs. unknown-viewer return
     * paths stay distinguishable.
     */
    private inline fun mutate(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
        transform: (Entry) -> Entry,
    ): Entry? {
        val bucket = overridesByViewer.getOrPut(viewer) { HashMap() }
        val key = Key(world, position)
        val previous = bucket[key] ?: Entry()
        val next = transform(previous)
        if (next == previous) return null
        if (next.isEmpty) {
            bucket.remove(key)
            if (bucket.isEmpty()) overridesByViewer.remove(viewer)
        } else {
            bucket[key] = next
        }
        return next
    }

    private fun sweep(viewer: PlayerId, reason: ClearReason): Int {
        val bucket = overridesByViewer.remove(viewer) ?: return 0
        val count = bucket.size
        if (count > 0) {
            eventBus?.publish(
                PerViewerOverridesCleared(
                    viewer = viewer,
                    gatewayFamily = PerViewerOverridesCleared.FAMILY_BLOCK,
                    reason = reason,
                    clearedCount = count,
                ),
            )
        }
        return count
    }
}
