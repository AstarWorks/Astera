package com.astarworks.astera.core.domain.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlin.math.roundToInt

/**
 * Sealed tree of HUD rendering primitives (Wave 5j, ADR-0097).
 *
 * The Wave 6b `PaperHudGateway` converts each variant to an Adventure
 * `Component` via the Worker γ `HudRenderer`. Sealed so the renderer's
 * `when` is exhaustive — adding a new variant (e.g. `Animation` for
 * tick-driven glyph cycling) is a deliberate change.
 *
 * **Vendor-neutral by construction**: no type in this file imports
 * `org.bukkit.*` / `io.papermc.*` / `org.kyori.adventure.*`. The renderer
 * does the conversion at the edge.
 */
public sealed interface HudComponent {

    /**
     * Plain i18n text. The resolved string is rendered with the player's
     * locale via [com.astarworks.astera.core.application.port.outbound.IMessageRenderer].
     *
     * @property messageKey i18n key resolved by the renderer.
     * @property color optional tint override. If `null`, the renderer
     *   inherits whatever the surrounding context (BossBar / ActionBar)
     *   defaults to. **MiniMessage note**: when the resolved string already
     *   contains MiniMessage color tags (e.g. `<red>foo</red>`), the
     *   `color` override is applied as the **outer** color and inner
     *   `<red>` segments still win for their own spans. See Wave 6b
     *   `HudRenderer` for the exact composition rules.
     */
    public data class Text(
        public val messageKey: MessageKey,
        public val color: HudColor? = null,
    ) : HudComponent

    /**
     * A single custom-font glyph (negative-space-font technique).
     *
     * @property textureRef stable ref into the [ICustomFontRegistry].
     * @property widthPx logical advance width in MC pixels. Used for layout
     *   math; the actual rendering uses the glyph's own width metadata.
     * @property ascent vertical offset adjustment, see font.json `ascent`.
     */
    public data class Glyph(
        public val textureRef: HudGlyphRef,
        public val widthPx: Int,
        public val ascent: Int = 0,
    ) : HudComponent {
        init {
            require(widthPx in 1..256) { "Glyph.widthPx must be 1..256, was $widthPx" }
        }
    }

    /**
     * Horizontal progress bar rendered as a sequence of `fillGlyph` /
     * `emptyGlyph` characters in a custom font.
     *
     * @property percent fill ratio, 0.0..1.0 inclusive.
     * @property fillGlyph glyph used for filled segments.
     * @property emptyGlyph glyph used for empty segments.
     * @property widthSegments total segment count, 1..100.
     */
    public data class ProgressBar(
        public val percent: Float,
        public val fillGlyph: HudGlyphRef,
        public val emptyGlyph: HudGlyphRef,
        public val widthSegments: Int = 20,
    ) : HudComponent {
        init {
            require(percent in 0f..1f) { "ProgressBar.percent must be in 0..1, was $percent" }
            require(widthSegments in 1..100) { "ProgressBar.widthSegments must be in 1..100" }
        }

        /**
         * Number of filled segments after rounding. Always in `0..widthSegments`.
         * The renderer emits this many [fillGlyph]s followed by
         * `(widthSegments - currentSegments())` [emptyGlyph]s.
         */
        public fun currentSegments(): Int = (percent * widthSegments).roundToInt()
    }

    /**
     * Horizontal concatenation of children. Use [Composite.of] for
     * ergonomic construction.
     *
     * @property parts children rendered in order.
     * @property spacing horizontal gap between children, in MC pixels.
     *   May be 0 (default) for flush packing, or used together with
     *   [Spacer] for finer per-pair control.
     */
    public data class Composite(
        public val parts: List<HudComponent>,
        public val spacing: Int = 0,
    ) : HudComponent {
        public companion object {
            /** Convenience varargs factory. `Composite.of(a, b, c)`. */
            public fun of(vararg parts: HudComponent): Composite = Composite(parts.toList())

            /** Convenience varargs factory with explicit `spacing`. */
            public fun of(spacing: Int, vararg parts: HudComponent): Composite =
                Composite(parts.toList(), spacing = spacing)
        }
    }

    /**
     * Pure horizontal spacing primitive (negative-space-font technique).
     *
     * @property widthPx advance width in MC pixels. **May be negative** —
     *   reverse-advance is the standard trick for stacking glyphs on top
     *   of each other in MC's left-to-right font renderer. See
     *   <https://minecraft.fandom.com/wiki/Resource_pack#Negative_space>.
     */
    public data class Spacer(
        public val widthPx: Int,
    ) : HudComponent {
        public companion object {
            /** Convenience factory mirroring [Spacer] for readability. */
            public fun px(width: Int): Spacer = Spacer(width)
        }
    }
}
