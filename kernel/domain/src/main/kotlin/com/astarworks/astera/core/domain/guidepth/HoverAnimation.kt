package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.animation.Easing

/**
 * Visual feedback for mouse-hover events on a [com.astarworks.astera.core.domain.gui.GuiSlot]
 * (Wave 5gui-depth, ADR-0114).
 *
 * Distinct from [SlotAnimation] because hover is **bidirectional** — the
 * animation runs forward when the cursor enters the slot and reverses when it
 * leaves. The adapter (Wave 6) is responsible for the enter/leave detection;
 * the domain just describes "what does hover look like".
 *
 * Sealed for exhaustive adapter dispatch. Two canonical variants cover the
 * Wave 5flex / Wave 5refine / Wave 5e tooltip expansion patterns.
 *
 * # Easing reuse
 *
 * [Scale] takes the Wave 5h [Easing] enum (32 standard curves). This wave
 * does **NOT** introduce a new easing curve.
 */
public sealed interface HoverAnimation {

    /** Hover-animation length in server ticks. */
    public val durationTicks: Long

    /**
     * Tint the slot with [overlayColorArgb] at [overlayAlpha] opacity while
     * hovered. The colour is encoded as a 32-bit ARGB value — the adapter
     * extracts R/G/B/A. Alpha is in `0.0..1.0`.
     *
     * Example: subtle white highlight = `overlayColorArgb = 0xFFFFFFFF.toInt()`,
     * `overlayAlpha = 0.25`, `durationTicks = 4`.
     */
    public data class Highlight(
        public val overlayColorArgb: Int,
        public val overlayAlpha: Double,
        override val durationTicks: Long,
    ) : HoverAnimation {
        init {
            require(overlayAlpha in 0.0..1.0) {
                "Highlight.overlayAlpha must be in 0.0..1.0 (got $overlayAlpha)"
            }
            require(durationTicks >= 0L) {
                "Highlight.durationTicks must be >= 0 (got $durationTicks)"
            }
        }
    }

    /**
     * Grow the slot item's render size from `1.0` to [peakScale] while
     * hovered, then shrink back when the cursor leaves. The adapter
     * interpolates with [easing].
     */
    public data class Scale(
        public val peakScale: Double,
        override val durationTicks: Long,
        public val easing: Easing,
    ) : HoverAnimation {
        init {
            require(peakScale > 1.0) {
                "Scale.peakScale must be > 1.0 to be a visible grow (got $peakScale)"
            }
            require(durationTicks >= 0L) {
                "Scale.durationTicks must be >= 0 (got $durationTicks)"
            }
        }
    }
}
