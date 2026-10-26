package com.astarworks.astera.core.domain.hologram.transition

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.hologram.BillboardMode
import com.astarworks.astera.core.domain.hologram.HologramBrightness
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.hologram.ItemDisplayMode
import com.astarworks.astera.core.domain.hologram.TextAlignment
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Vendor-neutral declarative transition for a Display-entity-backed
 * hologram (Wave 5transitions, ADR-0115 §B).
 *
 * Wave 5h shipped the [com.astarworks.astera.core.domain.hologram.Hologram]
 * aggregate with field-for-field Bukkit Display parity but no way to **tween**
 * those fields over time. Wave 5transitions fills that gap with two
 * categories of transition:
 *
 *  - **[Smooth]** — variants that interpolate a target value over
 *    `durationTicks` using an [Easing] curve. The transition controller
 *    samples the curve every tick and calls the corresponding per-type
 *    updater on [com.astarworks.astera.core.application.port.outbound.IHologramGateway].
 *  - **[Categorical]** — variants whose target field is non-interpolable
 *    (boolean, enum, item-id, block-key). The controller waits
 *    `delayTicks` then performs an instant set.
 *
 * Plus structural [Composite] (parallel execution) and structural [Timeline]
 * (sequential execution; see [HologramTimeline]).
 *
 * # Why a sealed hierarchy with this many variants
 *
 * The alternative — a flat `data class Transition(field: String, target:
 * Any, easing: Easing?)` — defeats Kotlin's compile-time exhaustiveness on
 * the controller's `when (transition)` step. Adding a 17th variant becomes
 * a runtime question; with this hierarchy it's a build-fail in the
 * controller. The variant count (18 + 1 composite) matches the per-type
 * updater surface 1-to-1 so the controller can dispatch field-by-field.
 *
 * # Negative durations / negative delays
 *
 * Constructor validation rejects `durationTicks < 0` and `delayTicks < 0`
 * via `require`. `durationTicks = 0` is **allowed** for Smooth variants —
 * the controller treats it as an instant final-value set (single tick
 * step), which is useful for "snap to target immediately" semantics in
 * composite contexts.
 */
public sealed interface HologramTransition {

    // ─── Smooth (interpolated) transitions ───

    /**
     * Tween [HologramTransform] (translation, leftRotation, scale,
     * rightRotation) toward [targetTransform] over [durationTicks].
     *
     * Translation + scale interpolate channel-wise via Vec3 lerp; both
     * rotations interpolate via JOML `Quaternionf.slerp(other, factor)`
     * which produces the shortest great-circle path on the unit
     * quaternion 4-sphere (preserves rotation magnitude as the angle goes
     * through the midpoint, unlike component-wise lerp which collapses
     * mid-tween).
     */
    public data class Transform(
        public val targetTransform: HologramTransform,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "Transform.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /**
     * Tween [com.astarworks.astera.core.domain.hologram.Hologram.brightness]
     * toward [targetBrightness] over [durationTicks]. `null` interpolates
     * block-light + sky-light channel-wise; if start or end is `null` the
     * controller treats the non-null side as `(0, 0)` for the tween.
     */
    public data class Brightness(
        public val targetBrightness: HologramBrightness?,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "Brightness.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /**
     * Tween [com.astarworks.astera.core.domain.hologram.Hologram.glowColor]
     * toward [targetColor] over [durationTicks]. RGBA channel-wise via
     * [HologramColorInterpolator]; `null` start or end treats that side as
     * fully-transparent black `(0, 0, 0, 0)`.
     */
    public data class GlowColor(
        public val targetColor: HologramColor?,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "GlowColor.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /** Tween [com.astarworks.astera.core.domain.hologram.Hologram.shadowRadius] toward [targetRadius]. */
    public data class ShadowRadius(
        public val targetRadius: Float,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "ShadowRadius.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /** Tween [com.astarworks.astera.core.domain.hologram.Hologram.shadowStrength] toward [targetStrength]. */
    public data class ShadowStrength(
        public val targetStrength: Float,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "ShadowStrength.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /** Tween [com.astarworks.astera.core.domain.hologram.Hologram.viewRangeBlocks] toward [targetRange]. */
    public data class ViewRange(
        public val targetRange: Float,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "ViewRange.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /**
     * Tween [com.astarworks.astera.core.domain.hologram.HologramKind.Text.textOpacity]
     * toward [targetOpacity] over [durationTicks]. Bytes are tweened as
     * signed int values and re-cast at write time (per
     * [com.astarworks.astera.core.domain.hologram.HologramColor] convention —
     * the Bukkit boundary does the unsigned widening).
     *
     * Requires the hologram's current kind to be
     * [com.astarworks.astera.core.domain.hologram.HologramKind.Text] at play
     * time; the controller fails fast with
     * [com.astarworks.astera.core.application.port.outbound.HologramError.TypeMismatch].
     */
    public data class TextOpacity(
        public val targetOpacity: Byte,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "TextOpacity.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /**
     * Tween [com.astarworks.astera.core.domain.hologram.HologramKind.Text.backgroundColor]
     * toward [targetColor]. RGBA channel-wise; same null-handling as
     * [GlowColor]. Requires Text kind at play time.
     */
    public data class TextBackgroundColor(
        public val targetColor: HologramColor?,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "TextBackgroundColor.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /**
     * Tween [com.astarworks.astera.core.domain.hologram.HologramKind.Text.lineWidth]
     * toward [targetWidth] over [durationTicks]. Int values interpolate as
     * doubles and round at write time (`roundToInt()` half-to-even). Requires
     * Text kind at play time.
     */
    public data class TextLineWidth(
        public val targetWidth: Int,
        public val durationTicks: Int,
        public val easing: Easing,
    ) : HologramTransition {
        init {
            require(durationTicks >= 0) { "TextLineWidth.durationTicks must be >= 0, got $durationTicks" }
        }
    }

    // ─── Categorical (instant set after delay) transitions ───

    /**
     * Replace [com.astarworks.astera.core.domain.hologram.HologramKind.Text.lines]
     * with [targetLines] after [delayTicks]. Requires Text kind at play time.
     */
    public data class TextContent(
        public val targetLines: List<MessageKey>,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "TextContent.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    /** Set [com.astarworks.astera.core.domain.hologram.HologramKind.Text.alignment] after [delayTicks]. Requires Text. */
    public data class TextAlignmentSet(
        public val targetAlignment: TextAlignment,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "TextAlignmentSet.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    /** Set [com.astarworks.astera.core.domain.hologram.HologramKind.Text.seeThrough] after [delayTicks]. Requires Text. */
    public data class TextSeeThrough(
        public val targetSeeThrough: Boolean,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "TextSeeThrough.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    /** Set [com.astarworks.astera.core.domain.hologram.HologramKind.Text.shadowed] after [delayTicks]. Requires Text. */
    public data class TextShadowed(
        public val targetShadowed: Boolean,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "TextShadowed.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    /** Set [com.astarworks.astera.core.domain.hologram.HologramKind.Text.defaultBackground] after [delayTicks]. Requires Text. */
    public data class TextDefaultBackground(
        public val target: Boolean,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "TextDefaultBackground.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    /**
     * Swap [com.astarworks.astera.core.domain.hologram.HologramKind.Item.itemId]
     * to [targetItemId] after [delayTicks]. Requires
     * [com.astarworks.astera.core.domain.hologram.HologramKind.Item] kind at play time.
     */
    public data class ItemSwap(
        public val targetItemId: ItemId,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "ItemSwap.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    /**
     * Set [com.astarworks.astera.core.domain.hologram.HologramKind.Item.displayMode]
     * after [delayTicks]. Requires Item kind at play time.
     */
    public data class ItemDisplayModeSet(
        public val targetMode: ItemDisplayMode,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "ItemDisplayModeSet.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    /**
     * Set [com.astarworks.astera.core.domain.hologram.HologramKind.Block.blockKey]
     * after [delayTicks]. Requires
     * [com.astarworks.astera.core.domain.hologram.HologramKind.Block] at play time.
     */
    public data class BlockData(
        public val targetBlockKey: String,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "BlockData.delayTicks must be >= 0, got $delayTicks" }
            require(targetBlockKey.isNotBlank()) { "BlockData.targetBlockKey must not be blank" }
        }
    }

    /** Set [com.astarworks.astera.core.domain.hologram.Hologram.billboard] after [delayTicks]. Applies to every kind. */
    public data class Billboard(
        public val targetBillboard: BillboardMode,
        public val delayTicks: Int = 0,
    ) : HologramTransition {
        init {
            require(delayTicks >= 0) { "Billboard.delayTicks must be >= 0, got $delayTicks" }
        }
    }

    // ─── Structural transitions ───

    /**
     * Run every child transition in parallel. The composite completes when
     * **every** child completes; cancellation cascades to all children.
     *
     * Useful for "fade in + slide up + rotate" combo at the same start
     * instant. Sequential composition uses [HologramTimeline] instead.
     */
    public data class Composite(
        public val transitions: List<HologramTransition>,
    ) : HologramTransition {
        init {
            require(transitions.isNotEmpty()) { "Composite.transitions must not be empty" }
        }
    }
}
