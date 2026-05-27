package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.HologramError
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.BillboardMode
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramBrightness
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.HologramInterpolation
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.hologram.HologramVisibilityScope
import com.astarworks.astera.core.domain.hologram.ItemDisplayMode
import com.astarworks.astera.core.domain.hologram.TextAlignment
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IHologramGateway] for use case tests (Wave 5h, ADR-0079 §1;
 * per-type updaters Wave 5transitions, ADR-0115 §A).
 *
 * Beyond storing live holograms in a [ConcurrentHashMap], every operation
 * is appended to [operations] as a [HologramOp] so tests can assert on the
 * full call sequence (not just the final state). Production
 * `InMemoryHologramGateway` drops the history list to avoid an unbounded
 * memory leak in long-running servers.
 *
 * Test cleanup: call [clear] in `@AfterEach` if reusing one instance across
 * tests — the gateway has no per-test lifecycle scope on its own.
 *
 * Backed by [ConcurrentHashMap] because Wave 6b's `PaperHologramGateway`
 * will receive cross-thread events (Bukkit packet listeners, Folia regions)
 * and we want the test double to share that mental model.
 */
public class FakeHologramGateway(
    /**
     * Returns the currently-online player set (Wave 5h-fix2 [viewers]
     * resolution for `Everyone` scope). Default = empty.
     */
    private val onlinePlayersProvider: () -> Set<PlayerId> = { emptySet() },
    /**
     * Returns the player members of `(matchId, teamId)` (Wave 5h-fix2
     * [viewers] resolution for `PerTeam` scope). Default = always empty.
     */
    private val teamMembersProvider: (MatchId, TeamId) -> Set<PlayerId> = { _, _ -> emptySet() },
) : IHologramGateway {

    /**
     * Operation history. Tests assert on this list to verify the use case
     * called the gateway with the right arguments in the right order.
     */
    public sealed interface HologramOp {
        public data class Spawned(val handle: HologramHandle, val hologram: Hologram) : HologramOp
        public data class Despawned(val handle: HologramHandle) : HologramOp
        public data class TransformUpdated(val handle: HologramHandle, val transform: HologramTransform) : HologramOp
        public data class KindUpdated(val handle: HologramHandle, val kind: HologramKind) : HologramOp
        public data class Teleported(val handle: HologramHandle, val newPosition: Vec3, val newWorld: WorldId?) : HologramOp
        public data class VisibilityUpdated(val handle: HologramHandle, val scope: HologramVisibilityScope) : HologramOp
        public data class BrightnessUpdated(val handle: HologramHandle, val brightness: HologramBrightness?) : HologramOp
        public data class InterpolationUpdated(val handle: HologramHandle, val interpolation: HologramInterpolation) : HologramOp
        public data class GlowColorUpdated(val handle: HologramHandle, val color: HologramColor?) : HologramOp

        // ─── Wave 5transitions, ADR-0115 §A per-type updaters ───
        public data class ShadowRadiusUpdated(val handle: HologramHandle, val radius: Float) : HologramOp
        public data class ShadowStrengthUpdated(val handle: HologramHandle, val strength: Float) : HologramOp
        public data class ViewRangeUpdated(val handle: HologramHandle, val range: Float) : HologramOp
        public data class BillboardUpdated(val handle: HologramHandle, val billboard: BillboardMode) : HologramOp
        public data class DisplayDimensionsUpdated(val handle: HologramHandle, val width: Float, val height: Float) : HologramOp
        public data class TextContentUpdated(val handle: HologramHandle, val lines: List<MessageKey>) : HologramOp
        public data class TextLineWidthUpdated(val handle: HologramHandle, val width: Int) : HologramOp
        public data class TextBackgroundColorUpdated(val handle: HologramHandle, val color: HologramColor?) : HologramOp
        public data class TextOpacityUpdated(val handle: HologramHandle, val opacity: Byte) : HologramOp
        public data class TextAlignmentUpdated(val handle: HologramHandle, val alignment: TextAlignment) : HologramOp
        public data class TextSeeThroughUpdated(val handle: HologramHandle, val seeThrough: Boolean) : HologramOp
        public data class TextShadowedUpdated(val handle: HologramHandle, val shadowed: Boolean) : HologramOp
        public data class TextDefaultBackgroundUpdated(val handle: HologramHandle, val hasBackground: Boolean) : HologramOp
        public data class ItemIdUpdated(val handle: HologramHandle, val itemId: ItemId) : HologramOp
        public data class ItemDisplayModeUpdated(val handle: HologramHandle, val mode: ItemDisplayMode) : HologramOp
        public data class BlockDataUpdated(val handle: HologramHandle, val blockKey: String) : HologramOp
    }

    private val live: ConcurrentHashMap<HologramHandle, Hologram> = ConcurrentHashMap()
    private val ops: MutableList<HologramOp> = mutableListOf()

    override fun spawn(hologram: Hologram): Result<HologramHandle, HologramError> {
        val handle = HologramHandle(UUID.randomUUID().toString())
        live[handle] = hologram
        ops += HologramOp.Spawned(handle, hologram)
        return Result.success(handle)
    }

    override fun despawn(handle: HologramHandle): Result<Unit, HologramError> {
        // Per port KDoc: despawn is idempotent — unknown handle returns Success.
        live.remove(handle)
        ops += HologramOp.Despawned(handle)
        return Result.success(Unit)
    }

    override fun updateTransform(
        handle: HologramHandle,
        transform: HologramTransform,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(transform = transform)
        ops += HologramOp.TransformUpdated(handle, transform)
        return Result.success(Unit)
    }

    override fun updateKind(
        handle: HologramHandle,
        kind: HologramKind,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(kind = kind)
        ops += HologramOp.KindUpdated(handle, kind)
        return Result.success(Unit)
    }

    override fun teleport(
        handle: HologramHandle,
        newPosition: Vec3,
        newWorld: WorldId?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(
            position = newPosition,
            worldId = newWorld ?: current.worldId,
        )
        ops += HologramOp.Teleported(handle, newPosition, newWorld)
        return Result.success(Unit)
    }

    override fun updateVisibility(
        handle: HologramHandle,
        scope: HologramVisibilityScope,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(visibilityScope = scope)
        ops += HologramOp.VisibilityUpdated(handle, scope)
        return Result.success(Unit)
    }

    override fun updateBrightness(
        handle: HologramHandle,
        brightness: HologramBrightness?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(brightness = brightness)
        ops += HologramOp.BrightnessUpdated(handle, brightness)
        return Result.success(Unit)
    }

    override fun updateInterpolation(
        handle: HologramHandle,
        interpolation: HologramInterpolation,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(interpolation = interpolation)
        ops += HologramOp.InterpolationUpdated(handle, interpolation)
        return Result.success(Unit)
    }

    override fun updateGlowColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(glowColor = color)
        ops += HologramOp.GlowColorUpdated(handle, color)
        return Result.success(Unit)
    }

    // ─── Display base per-type updaters (Wave 5transitions, ADR-0115 §A) ───

    override fun updateShadowRadius(handle: HologramHandle, radius: Float): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(shadowRadius = radius)
        ops += HologramOp.ShadowRadiusUpdated(handle, radius)
        return Result.success(Unit)
    }

    override fun updateShadowStrength(handle: HologramHandle, strength: Float): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(shadowStrength = strength)
        ops += HologramOp.ShadowStrengthUpdated(handle, strength)
        return Result.success(Unit)
    }

    override fun updateViewRange(handle: HologramHandle, range: Float): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(viewRangeBlocks = range)
        ops += HologramOp.ViewRangeUpdated(handle, range)
        return Result.success(Unit)
    }

    override fun updateBillboard(
        handle: HologramHandle,
        billboard: BillboardMode,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(billboard = billboard)
        ops += HologramOp.BillboardUpdated(handle, billboard)
        return Result.success(Unit)
    }

    override fun updateDisplayDimensions(
        handle: HologramHandle,
        width: Float,
        height: Float,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(displayWidth = width, displayHeight = height)
        ops += HologramOp.DisplayDimensionsUpdated(handle, width, height)
        return Result.success(Unit)
    }

    // ─── Text-specific updaters (Wave 5transitions, ADR-0115 §A) ───

    override fun updateTextContent(
        handle: HologramHandle,
        lines: List<MessageKey>,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(lines = lines) }) {
        ops += HologramOp.TextContentUpdated(handle, lines)
    }

    override fun updateTextLineWidth(
        handle: HologramHandle,
        width: Int,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(lineWidth = width) }) {
        ops += HologramOp.TextLineWidthUpdated(handle, width)
    }

    override fun updateTextBackgroundColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(backgroundColor = color) }) {
        ops += HologramOp.TextBackgroundColorUpdated(handle, color)
    }

    override fun updateTextOpacity(
        handle: HologramHandle,
        opacity: Byte,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(textOpacity = opacity) }) {
        ops += HologramOp.TextOpacityUpdated(handle, opacity)
    }

    override fun updateTextAlignment(
        handle: HologramHandle,
        alignment: TextAlignment,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(alignment = alignment) }) {
        ops += HologramOp.TextAlignmentUpdated(handle, alignment)
    }

    override fun updateTextSeeThrough(
        handle: HologramHandle,
        seeThrough: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(seeThrough = seeThrough) }) {
        ops += HologramOp.TextSeeThroughUpdated(handle, seeThrough)
    }

    override fun updateTextShadowed(
        handle: HologramHandle,
        shadowed: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(shadowed = shadowed) }) {
        ops += HologramOp.TextShadowedUpdated(handle, shadowed)
    }

    override fun updateTextDefaultBackground(
        handle: HologramHandle,
        hasBackground: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle, { it.copy(defaultBackground = hasBackground) }) {
        ops += HologramOp.TextDefaultBackgroundUpdated(handle, hasBackground)
    }

    // ─── Item-specific updaters (Wave 5transitions, ADR-0115 §A) ───

    override fun updateItemId(
        handle: HologramHandle,
        itemId: ItemId,
    ): Result<Unit, HologramError> = mutateItem(handle, { it.copy(itemId = itemId) }) {
        ops += HologramOp.ItemIdUpdated(handle, itemId)
    }

    override fun updateItemDisplayMode(
        handle: HologramHandle,
        mode: ItemDisplayMode,
    ): Result<Unit, HologramError> = mutateItem(handle, { it.copy(displayMode = mode) }) {
        ops += HologramOp.ItemDisplayModeUpdated(handle, mode)
    }

    // ─── Block-specific updater (Wave 5transitions, ADR-0115 §A) ───

    override fun updateBlockData(
        handle: HologramHandle,
        blockKey: String,
    ): Result<Unit, HologramError> = mutateBlock(handle, { it.copy(blockKey = blockKey) }) {
        ops += HologramOp.BlockDataUpdated(handle, blockKey)
    }

    override fun viewers(handle: HologramHandle): Collection<PlayerId> {
        val hologram = live[handle] ?: return emptyList()
        return when (val scope = hologram.visibilityScope) {
            HologramVisibilityScope.Everyone -> onlinePlayersProvider()
            is HologramVisibilityScope.PerPlayer -> scope.playerIds
                .intersect(onlinePlayersProvider().ifEmpty { scope.playerIds })
                .toList()
            is HologramVisibilityScope.PerTeam -> teamMembersProvider(scope.matchId, scope.teamId)
                .toList()
        }
    }

    override fun all(): Collection<HologramHandle> = live.keys.toList()

    override fun snapshot(handle: HologramHandle): Hologram? = live[handle]

    /**
     * Test accessor: snapshot of the hologram currently associated with [handle], or `null` if despawned/unknown.
     *
     * Retained for backwards compatibility with Wave 5h tests that called
     * `current(handle)`; new code should prefer the port-level [snapshot]
     * accessor (Wave 5transitions, ADR-0115 §A).
     */
    public fun current(handle: HologramHandle): Hologram? = live[handle]

    /** Test accessor: immutable snapshot of every operation invoked so far, in call order. */
    public fun operations(): List<HologramOp> = ops.toList()

    /** Test cleanup hook — drops live state and history. Useful in `@AfterEach`. */
    public fun clear() {
        live.clear()
        ops.clear()
    }

    // ─── Per-type mutation helpers (Wave 5transitions, ADR-0115 §A) ───
    //
    // Inline + crossinline lambdas keep the per-method body small: each
    // override is a single statement, the helper checks UnknownHandle +
    // TypeMismatch + writes the op-history entry only after a successful
    // copy. The `recordOp` lambda is crossinline so it can call into the
    // outer `ops` MutableList without an allocation.

    private inline fun mutateText(
        handle: HologramHandle,
        transform: (HologramKind.Text) -> HologramKind.Text,
        recordOp: () -> Unit,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        val kind = current.kind
        if (kind !is HologramKind.Text) {
            return Result.failure(
                HologramError.TypeMismatch(
                    expected = HologramKind.Text::class,
                    actual = kind::class,
                ),
            )
        }
        live[handle] = current.copy(kind = transform(kind))
        recordOp()
        return Result.success(Unit)
    }

    private inline fun mutateItem(
        handle: HologramHandle,
        transform: (HologramKind.Item) -> HologramKind.Item,
        recordOp: () -> Unit,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        val kind = current.kind
        if (kind !is HologramKind.Item) {
            return Result.failure(
                HologramError.TypeMismatch(
                    expected = HologramKind.Item::class,
                    actual = kind::class,
                ),
            )
        }
        live[handle] = current.copy(kind = transform(kind))
        recordOp()
        return Result.success(Unit)
    }

    private inline fun mutateBlock(
        handle: HologramHandle,
        transform: (HologramKind.Block) -> HologramKind.Block,
        recordOp: () -> Unit,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        val kind = current.kind
        if (kind !is HologramKind.Block) {
            return Result.failure(
                HologramError.TypeMismatch(
                    expected = HologramKind.Block::class,
                    actual = kind::class,
                ),
            )
        }
        live[handle] = current.copy(kind = transform(kind))
        recordOp()
        return Result.success(Unit)
    }
}
