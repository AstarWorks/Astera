package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.gui.SlotIndex

/**
 * Per-slot visual animation spec (Wave 5gui-depth, ADR-0114).
 *
 * Models the four canonical slot animation kinds used by Wave 5flex profile
 * card reveal / Wave 5refine refinement toast / Wave 5e quickbar swap / Wave
 * 5bet odds tick:
 *
 *  - [Fade] — opacity tween (item glints in or fades out)
 *  - [Slide] — slot-index translation (item visually moves from one slot to
 *    another within the same GUI)
 *  - [Bounce] — vertical bob (item briefly hops; uses [Easing.EASE_OUT_BOUNCE]
 *    semantics)
 *  - [Pulse] — scale oscillation between [Pulse.scaleMin] and [Pulse.scaleMax]
 *
 * Each variant carries its own duration + easing (where applicable). Sealed so
 * adapter `when` dispatch is exhaustive — adding a new animation kind is a
 * deliberate cross-cutting change rather than something a contributor can miss.
 *
 * # Easing reuse
 *
 * [Fade] / [Slide] / [Bounce] / [Pulse] take the Wave 5h [Easing] enum
 * (32 standard curves). This wave does **NOT** introduce a new easing curve.
 *
 * # Vendor-neutrality
 *
 * No Bukkit `ItemStack`, no packet types. The Paper adapter (Wave 6) translates
 * each variant to packet-level effects — `Fade` becomes an `EntityMetadata`
 * alpha interpolation, `Slide` becomes a temporary armor-stand display entity
 * tween, etc. The domain just declares intent.
 */
public sealed interface SlotAnimation {

    /** Animation length in server ticks. Negative durations are rejected at the variant `init`. */
    public val durationTicks: Long

    /**
     * Opacity tween. [fromAlpha] / [toAlpha] are in `0.0..1.0` (0 = invisible,
     * 1 = fully opaque).
     */
    public data class Fade(
        public val fromAlpha: Double,
        public val toAlpha: Double,
        override val durationTicks: Long,
        public val easing: Easing,
    ) : SlotAnimation {
        init {
            require(fromAlpha in 0.0..1.0) { "Fade.fromAlpha must be in 0.0..1.0 (got $fromAlpha)" }
            require(toAlpha in 0.0..1.0) { "Fade.toAlpha must be in 0.0..1.0 (got $toAlpha)" }
            require(durationTicks >= 0L) { "Fade.durationTicks must be >= 0 (got $durationTicks)" }
        }
    }

    /**
     * Slot-index translation. The adapter interpolates a visual cursor between
     * [fromSlot] and [toSlot] over [durationTicks]; the actual GUI slot map
     * stays untouched (animation is purely cosmetic).
     */
    public data class Slide(
        public val fromSlot: SlotIndex,
        public val toSlot: SlotIndex,
        override val durationTicks: Long,
        public val easing: Easing,
    ) : SlotAnimation {
        init {
            require(durationTicks >= 0L) { "Slide.durationTicks must be >= 0 (got $durationTicks)" }
        }
    }

    /**
     * Vertical hop. [amplitude] is in fractional slot heights (0.5 = half a
     * slot bob). The adapter uses [Easing.EASE_OUT_BOUNCE] for the curve;
     * the variant intentionally fixes the easing so callers don't accidentally
     * pass [Easing.LINEAR] and lose the bounce character.
     */
    public data class Bounce(
        public val amplitude: Double,
        override val durationTicks: Long,
    ) : SlotAnimation {
        init {
            require(amplitude > 0.0) { "Bounce.amplitude must be > 0 (got $amplitude)" }
            require(durationTicks >= 0L) { "Bounce.durationTicks must be >= 0 (got $durationTicks)" }
        }
    }

    /**
     * Scale oscillation between [scaleMin] and [scaleMax]. The adapter loops
     * `min → max → min` once across [durationTicks]; multi-cycle pulse is the
     * caller's responsibility (chain multiple `animateSlot` calls).
     */
    public data class Pulse(
        public val scaleMin: Double,
        public val scaleMax: Double,
        override val durationTicks: Long,
    ) : SlotAnimation {
        init {
            require(scaleMin > 0.0) { "Pulse.scaleMin must be > 0 (got $scaleMin)" }
            require(scaleMax > 0.0) { "Pulse.scaleMax must be > 0 (got $scaleMax)" }
            require(scaleMax >= scaleMin) {
                "Pulse.scaleMax must be >= scaleMin (got max=$scaleMax min=$scaleMin)"
            }
            require(durationTicks >= 0L) { "Pulse.durationTicks must be >= 0 (got $durationTicks)" }
        }
    }
}
