package com.astarworks.astera.core.application.port.outbound

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
import kotlin.reflect.KClass

/**
 * Outbound port: spawn / despawn / update Display-entity-backed holograms
 * (Wave 5h, ADR-0079 §1; per-type hot-update API expanded Wave 5transitions,
 * ADR-0115 §A).
 *
 * Wave 5h ships `InMemoryHologramGateway` + `FakeHologramGateway`. Wave 6b
 * lands `PaperHologramGateway` over Bukkit `TextDisplay` / `ItemDisplay` /
 * `BlockDisplay` entities (`org.bukkit.entity.Display` hierarchy).
 *
 * Vendor-neutral by construction: the domain uses
 * [com.astarworks.astera.core.domain.hologram.HologramTransform] which
 * wraps `org.joml.Quaternionf` (pure-math library, ADR-0079 §1 / 5h-fix).
 * The Paper adapter unwraps the `Transformation` for `Display.transformation`
 * — no domain → adapter type translation needed.
 *
 * Per Konsist `Hologram domain must not import Bukkit Display family`, no
 * type referenced from `core.domain.hologram.*` may import `org.bukkit.*` —
 * `org.joml.*` is explicitly allowed (pure-math, no Minecraft coupling).
 *
 * # Per-type updaters (Wave 5transitions, ADR-0115 §A)
 *
 * Wave 5h shipped only [updateKind] (full payload swap). Wave 5transitions
 * adds 16 field-level updaters so the transition controller
 * ([com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController])
 * can tween individual fields per tick without rebuilding the whole [HologramKind]
 * variant on every step.
 *
 * The updaters split three ways:
 *  - **Display base** (5): `shadowRadius`, `shadowStrength`, `viewRange`,
 *    `billboard`, `displayDimensions` — apply to every kind variant.
 *  - **Text-specific** (8): `textContent`, `textLineWidth`, `textBackgroundColor`,
 *    `textOpacity`, `textAlignment`, `textSeeThrough`, `textShadowed`,
 *    `textDefaultBackground` — require [HologramKind.Text].
 *  - **Item-specific** (2): `itemId`, `itemDisplayMode` — require [HologramKind.Item].
 *  - **Block-specific** (1): `blockData` — requires [HologramKind.Block].
 *
 * Per-type updaters fail fast with [HologramError.TypeMismatch] when the
 * hologram's current kind does not match. Callers MUST pattern-match on
 * the kind before calling a per-type updater, or accept the failure path —
 * the type system cannot enforce this without a per-kind-typed handle, and
 * that would complicate the [com.astarworks.astera.core.domain.hologram.HologramHandle]
 * value class without proportional safety win (transitions tend to be
 * scheduled against a kind known at composition time).
 */
public interface IHologramGateway {

    /**
     * Spawn [hologram] in the world at its declared position. Returns an
     * opaque [HologramHandle] callers store for later [despawn] /
     * [updateTransform] / [updateKind] / [teleport].
     *
     * Idempotent per [Hologram.ownerKey]: when the key is set and a live hologram already carries it,
     * the existing handle is returned and nothing new is spawned.
     */
    public fun spawn(hologram: Hologram): Result<HologramHandle, HologramError>

    /** Remove a previously-spawned hologram. Idempotent: undoing an unknown handle returns Success. */
    public fun despawn(handle: HologramHandle): Result<Unit, HologramError>

    /**
     * Replace the transform (translation + rotation + scale) on a live
     * hologram. Used by the reactive binding pipeline for animation.
     */
    public fun updateTransform(handle: HologramHandle, transform: HologramTransform): Result<Unit, HologramError>

    /**
     * Hot-swap the kind payload (text content / item / block). The hologram
     * stays at its current position with its current transform.
     */
    public fun updateKind(handle: HologramHandle, kind: HologramKind): Result<Unit, HologramError>

    /** Teleport a live hologram to a new world-position without despawning. */
    public fun teleport(handle: HologramHandle, newPosition: Vec3, newWorld: WorldId? = null): Result<Unit, HologramError>

    /**
     * Replace the visibility scope on a live hologram (Wave 5h-fix2).
     *
     * Used when a PerTeam hologram needs to surface to a player who joined
     * the team mid-match, or when a hidden hologram should become
     * Everyone-visible (boss reveal). Paper impl (Wave 6b) recomputes the
     * per-player entity-create/destroy packet set.
     */
    public fun updateVisibility(
        handle: HologramHandle,
        scope: HologramVisibilityScope,
    ): Result<Unit, HologramError>

    /**
     * Update the brightness override (Wave 5h-fix2). `null` resets to world
     * brightness. Used by anomalies / ambient pulse effects.
     */
    public fun updateBrightness(
        handle: HologramHandle,
        brightness: HologramBrightness?,
    ): Result<Unit, HologramError>

    /**
     * Update the interpolation parameters (Wave 5h-fix2). Wave 6b Paper impl
     * writes `Display.interpolationDuration / interpolationDelay /
     * teleportDuration`.
     */
    public fun updateInterpolation(
        handle: HologramHandle,
        interpolation: HologramInterpolation,
    ): Result<Unit, HologramError>

    /**
     * Update the glow color override (Wave 5h-fix2). `null` removes the glow.
     */
    public fun updateGlowColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError>

    // ─── Display base per-type updaters (Wave 5transitions, ADR-0115 §A) ───

    /** Update [Hologram.shadowRadius]. Applies to every kind variant. */
    public fun updateShadowRadius(handle: HologramHandle, radius: Float): Result<Unit, HologramError>

    /** Update [Hologram.shadowStrength]. Applies to every kind variant. */
    public fun updateShadowStrength(handle: HologramHandle, strength: Float): Result<Unit, HologramError>

    /** Update [Hologram.viewRangeBlocks]. Applies to every kind variant. */
    public fun updateViewRange(handle: HologramHandle, range: Float): Result<Unit, HologramError>

    /** Update [Hologram.billboard]. Applies to every kind variant. */
    public fun updateBillboard(handle: HologramHandle, billboard: BillboardMode): Result<Unit, HologramError>

    /**
     * Update [Hologram.displayWidth] + [Hologram.displayHeight] atomically.
     * Both fields update in a single call because Bukkit's `Display` exposes
     * them as a paired bounding-box override (setting only one yields visual
     * stretching on Wave 6b adapter); the transition controller tweens both
     * with the same easing curve.
     */
    public fun updateDisplayDimensions(handle: HologramHandle, width: Float, height: Float): Result<Unit, HologramError>

    // ─── Text-specific updaters (Wave 5transitions, ADR-0115 §A) ───

    /**
     * Update the i18n line list on a [HologramKind.Text] hologram. Fails
     * with [HologramError.TypeMismatch] if the current kind is not `Text`.
     */
    public fun updateTextContent(handle: HologramHandle, lines: List<MessageKey>): Result<Unit, HologramError>

    /** Update [HologramKind.Text.lineWidth]. Fails with [HologramError.TypeMismatch] if not Text. */
    public fun updateTextLineWidth(handle: HologramHandle, width: Int): Result<Unit, HologramError>

    /** Update [HologramKind.Text.backgroundColor]. `null` clears. Fails with [HologramError.TypeMismatch] if not Text. */
    public fun updateTextBackgroundColor(handle: HologramHandle, color: HologramColor?): Result<Unit, HologramError>

    /** Update [HologramKind.Text.textOpacity]. Fails with [HologramError.TypeMismatch] if not Text. */
    public fun updateTextOpacity(handle: HologramHandle, opacity: Byte): Result<Unit, HologramError>

    /** Update [HologramKind.Text.alignment]. Fails with [HologramError.TypeMismatch] if not Text. */
    public fun updateTextAlignment(handle: HologramHandle, alignment: TextAlignment): Result<Unit, HologramError>

    /** Update [HologramKind.Text.seeThrough]. Fails with [HologramError.TypeMismatch] if not Text. */
    public fun updateTextSeeThrough(handle: HologramHandle, seeThrough: Boolean): Result<Unit, HologramError>

    /** Update [HologramKind.Text.shadowed]. Fails with [HologramError.TypeMismatch] if not Text. */
    public fun updateTextShadowed(handle: HologramHandle, shadowed: Boolean): Result<Unit, HologramError>

    /** Update [HologramKind.Text.defaultBackground]. Fails with [HologramError.TypeMismatch] if not Text. */
    public fun updateTextDefaultBackground(handle: HologramHandle, hasBackground: Boolean): Result<Unit, HologramError>

    // ─── Item-specific updaters (Wave 5transitions, ADR-0115 §A) ───

    /** Update [HologramKind.Item.itemId]. Fails with [HologramError.TypeMismatch] if not Item. */
    public fun updateItemId(handle: HologramHandle, itemId: ItemId): Result<Unit, HologramError>

    /** Update [HologramKind.Item.displayMode]. Fails with [HologramError.TypeMismatch] if not Item. */
    public fun updateItemDisplayMode(handle: HologramHandle, mode: ItemDisplayMode): Result<Unit, HologramError>

    // ─── Block-specific updater (Wave 5transitions, ADR-0115 §A) ───

    /** Update [HologramKind.Block.blockKey]. Fails with [HologramError.TypeMismatch] if not Block. */
    public fun updateBlockData(handle: HologramHandle, blockKey: String): Result<Unit, HologramError>

    /**
     * Currently-resolved viewer set for [handle] based on the current
     * [HologramVisibilityScope] (Wave 5h-fix2):
     *  - `Everyone` → all online players (Paper impl) / empty (test impls)
     *  - `PerPlayer(ids)` → exactly `ids` (Paper filters for online)
     *  - `PerTeam(matchId, teamId)` → team members (Paper filters for online)
     *
     * Returns empty on `UnknownHandle`. Used by Phase 4 admin dashboard
     * ("who's currently seeing this hologram?") and gameplay audits.
     */
    public fun viewers(handle: HologramHandle): Collection<PlayerId>

    /** Snapshot of currently live hologram handles. */
    public fun all(): Collection<HologramHandle>

    /**
     * Read-back of the live [Hologram] aggregate for [handle], or `null`
     * if the handle is unknown (Wave 5transitions, ADR-0115 §A).
     *
     * Wave 5h shipped this gateway as a write-only surface, but the
     * transition controller (ADR-0115 §F) needs to:
     *  1. Validate `transition.kind` against `hologram.kind` at play-time
     *     (returns [HologramTransitionError.TypeMismatch] if mismatched
     *     before scheduling).
     *  2. Capture the from-state for smooth-tween interpolation so the
     *     first sampled frame lerps from the value the user last set,
     *     not from a hard-coded default.
     *
     * The Wave 6b Paper adapter will populate this from
     * `Display.transformation` / `TextDisplay.text()` reads against the
     * spawned entity. The in-memory + fake gateways already track the
     * live aggregate in a `ConcurrentHashMap<HologramHandle, Hologram>`
     * — they just didn't expose a read accessor publicly until now.
     */
    public fun snapshot(handle: HologramHandle): Hologram?
}

public sealed class HologramError {
    public data class UnknownWorld(public val world: WorldId) : HologramError()
    public data class UnknownHandle(public val handle: HologramHandle) : HologramError()
    public data class InvalidTransform(public val detail: String) : HologramError()
    public data class Storage(public val detail: String) : HologramError()

    /**
     * A per-type updater (Wave 5transitions, ADR-0115 §A) was called against
     * a hologram whose current kind does not match the updater's expected
     * variant. Carries the [KClass] of both so callers can render a
     * type-safe error in logs / GUI.
     *
     * Example: calling `updateTextContent(handle, ...)` when the hologram
     * was spawned as [HologramKind.Item] produces
     * `TypeMismatch(expected = HologramKind.Text::class, actual = HologramKind.Item::class)`.
     */
    public data class TypeMismatch(
        public val expected: KClass<out HologramKind>,
        public val actual: KClass<out HologramKind>,
    ) : HologramError()
}
