package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.perviewer.block.IPerViewerBlockStateGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cross-plugin Fake for [IPerViewerBlockStateGateway] (Wave 5y-ext, ADR-0112).
 * See [FakePerViewerNpcStateGateway] for the design rationale; same shape.
 */
public class FakePerViewerBlockStateGateway : IPerViewerBlockStateGateway {

    public sealed interface Call {
        public val viewer: PlayerId
        public val world: WorldId
        public val position: Vec3

        public data class OverrideBlockType(
            override val viewer: PlayerId,
            override val world: WorldId,
            override val position: Vec3,
            val blockType: BlockType,
        ) : Call

        public data class OverrideTexture(
            override val viewer: PlayerId,
            override val world: WorldId,
            override val position: Vec3,
            val textureRef: HudGlyphRef,
        ) : Call

        public data class HideBlock(
            override val viewer: PlayerId,
            override val world: WorldId,
            override val position: Vec3,
        ) : Call

        public data class ShowBlock(
            override val viewer: PlayerId,
            override val world: WorldId,
            override val position: Vec3,
        ) : Call

        public data class ClearOverride(
            override val viewer: PlayerId,
            override val world: WorldId,
            override val position: Vec3,
        ) : Call
    }

    public val calls: MutableList<Call> = mutableListOf()

    private val rejectedViewers: MutableSet<PlayerId> = HashSet()
    private var backendFailure: String? = null
    private val activeByViewer: MutableMap<PlayerId, MutableSet<Pair<WorldId, Vec3>>> = HashMap()

    public fun rejectViewer(viewer: PlayerId): Unit { rejectedViewers += viewer }
    public fun failBackend(reason: String?): Unit { backendFailure = reason }

    override fun overrideBlockType(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
        blockType: BlockType,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideBlockType(viewer, world, position, blockType)
        return apply(viewer) { it += (world to position) }
    }

    override fun overrideTexture(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
        textureRef: HudGlyphRef,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.OverrideTexture(viewer, world, position, textureRef)
        return apply(viewer) { it += (world to position) }
    }

    override fun hideBlock(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.HideBlock(viewer, world, position)
        return apply(viewer) { it += (world to position) }
    }

    override fun showBlock(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.ShowBlock(viewer, world, position)
        return apply(viewer) { /* no-op */ }
    }

    override fun clearOverride(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError> {
        calls += Call.ClearOverride(viewer, world, position)
        return apply(viewer) { it -= (world to position) }
    }

    override fun clearAllOverridesForViewer(viewer: PlayerId): Int =
        activeByViewer.remove(viewer)?.size ?: 0

    public fun clear() {
        calls.clear()
        rejectedViewers.clear()
        backendFailure = null
        activeByViewer.clear()
    }

    private inline fun apply(
        viewer: PlayerId,
        transform: (MutableSet<Pair<WorldId, Vec3>>) -> Unit,
    ): Result<Unit, PerViewerOverrideError> {
        backendFailure?.let { return Result.failure(PerViewerOverrideError.BackendError(it)) }
        if (viewer in rejectedViewers) return Result.failure(PerViewerOverrideError.UnknownViewer)
        transform(activeByViewer.getOrPut(viewer) { HashSet() })
        return Result.success(Unit)
    }
}
