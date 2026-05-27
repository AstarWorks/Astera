package com.astarworks.astera.core.application.service

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
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IHologramGateway] (Wave 5h, ADR-0079 §1; per-type
 * updaters Wave 5transitions, ADR-0115 §A).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6b's
 * `PaperHologramGateway` will register at higher priority and shadow this
 * impl on real servers, but Phase 1 PoC tooling (tutorial scaffolding,
 * spectator overlays) gets a working gateway from day one.
 *
 * **Behaviour vs `FakeHologramGateway`:** identical contract, no operation
 * history. The fake keeps a `MutableList<HologramOp>` for test assertions;
 * the production in-memory impl drops it to avoid an unbounded memory
 * leak in long-running servers — a long-lived match might call
 * `updateTransform` thousands of times per minute on a single hologram and
 * the history would grow without bound. If you need observability in
 * production, layer a decorator gateway over this one rather than baking it
 * in here.
 *
 * **Not thread-safe** beyond what [ConcurrentHashMap] provides. Astera's
 * scheduler contract means hologram mutation runs on the server main
 * thread; the concurrent map is precautionary for cross-thread
 * Wave 6b packet listeners.
 */
public class InMemoryHologramGateway : IHologramGateway {

    private val live: ConcurrentHashMap<HologramHandle, Hologram> = ConcurrentHashMap()

    override fun spawn(hologram: Hologram): Result<HologramHandle, HologramError> {
        val handle = HologramHandle(UUID.randomUUID().toString())
        live[handle] = hologram
        return Result.success(handle)
    }

    override fun despawn(handle: HologramHandle): Result<Unit, HologramError> {
        // Per port KDoc: despawn is idempotent — unknown handle returns Success.
        live.remove(handle)
        return Result.success(Unit)
    }

    override fun updateTransform(
        handle: HologramHandle,
        transform: HologramTransform,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(transform = transform)
        return Result.success(Unit)
    }

    override fun updateKind(
        handle: HologramHandle,
        kind: HologramKind,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(kind = kind)
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
        return Result.success(Unit)
    }

    override fun updateVisibility(
        handle: HologramHandle,
        scope: HologramVisibilityScope,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(visibilityScope = scope)
        return Result.success(Unit)
    }

    override fun updateBrightness(
        handle: HologramHandle,
        brightness: HologramBrightness?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(brightness = brightness)
        return Result.success(Unit)
    }

    override fun updateInterpolation(
        handle: HologramHandle,
        interpolation: HologramInterpolation,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(interpolation = interpolation)
        return Result.success(Unit)
    }

    override fun updateGlowColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(glowColor = color)
        return Result.success(Unit)
    }

    // ─── Display base per-type updaters (Wave 5transitions, ADR-0115 §A) ───

    override fun updateShadowRadius(handle: HologramHandle, radius: Float): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(shadowRadius = radius)
        return Result.success(Unit)
    }

    override fun updateShadowStrength(handle: HologramHandle, strength: Float): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(shadowStrength = strength)
        return Result.success(Unit)
    }

    override fun updateViewRange(handle: HologramHandle, range: Float): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(viewRangeBlocks = range)
        return Result.success(Unit)
    }

    override fun updateBillboard(
        handle: HologramHandle,
        billboard: BillboardMode,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(billboard = billboard)
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
        return Result.success(Unit)
    }

    // ─── Text-specific updaters (Wave 5transitions, ADR-0115 §A) ───

    override fun updateTextContent(
        handle: HologramHandle,
        lines: List<MessageKey>,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(lines = lines) }

    override fun updateTextLineWidth(
        handle: HologramHandle,
        width: Int,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(lineWidth = width) }

    override fun updateTextBackgroundColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(backgroundColor = color) }

    override fun updateTextOpacity(
        handle: HologramHandle,
        opacity: Byte,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(textOpacity = opacity) }

    override fun updateTextAlignment(
        handle: HologramHandle,
        alignment: TextAlignment,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(alignment = alignment) }

    override fun updateTextSeeThrough(
        handle: HologramHandle,
        seeThrough: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(seeThrough = seeThrough) }

    override fun updateTextShadowed(
        handle: HologramHandle,
        shadowed: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(shadowed = shadowed) }

    override fun updateTextDefaultBackground(
        handle: HologramHandle,
        hasBackground: Boolean,
    ): Result<Unit, HologramError> = mutateText(handle) { it.copy(defaultBackground = hasBackground) }

    // ─── Item-specific updaters (Wave 5transitions, ADR-0115 §A) ───

    override fun updateItemId(handle: HologramHandle, itemId: ItemId): Result<Unit, HologramError> =
        mutateItem(handle) { it.copy(itemId = itemId) }

    override fun updateItemDisplayMode(
        handle: HologramHandle,
        mode: ItemDisplayMode,
    ): Result<Unit, HologramError> = mutateItem(handle) { it.copy(displayMode = mode) }

    // ─── Block-specific updater (Wave 5transitions, ADR-0115 §A) ───

    override fun updateBlockData(handle: HologramHandle, blockKey: String): Result<Unit, HologramError> =
        mutateBlock(handle) { it.copy(blockKey = blockKey) }

    /**
     * In-memory impl returns an empty viewer set. The real population happens
     * in Wave 6b `PaperHologramGateway` which has access to `Server.onlinePlayers`
     * and per-match team rosters. Production in-process callers should treat
     * empty as "no information" rather than "no one viewing".
     */
    override fun viewers(handle: HologramHandle): Collection<PlayerId> = emptyList()

    override fun all(): Collection<HologramHandle> = live.keys.toList()

    override fun snapshot(handle: HologramHandle): Hologram? = live[handle]

    // ─── Per-type mutation helpers (Wave 5transitions, ADR-0115 §A) ───

    private inline fun mutateText(
        handle: HologramHandle,
        transform: (HologramKind.Text) -> HologramKind.Text,
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
        return Result.success(Unit)
    }

    private inline fun mutateItem(
        handle: HologramHandle,
        transform: (HologramKind.Item) -> HologramKind.Item,
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
        return Result.success(Unit)
    }

    private inline fun mutateBlock(
        handle: HologramHandle,
        transform: (HologramKind.Block) -> HologramKind.Block,
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
        return Result.success(Unit)
    }
}
