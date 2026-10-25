package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * One line of injected tooltip content (Wave 5gui-depth, ADR-0114).
 *
 * Returned by [com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipExtensionProvider.extend]
 * and aggregated by `ITooltipRegistry.lookup`. The adapter (Wave 6) renders
 * each variant into the vanilla item-tooltip flow.
 *
 * # Why three variants
 *
 * - [Text] — plain message (most common). Wave 5flex rarity colour reuse
 *   [HudColor] from Wave 5j.
 * - [Image] — embedded custom-font glyph (Wave 5j [HudGlyphRef] reuse —
 *   refinement tier icons, status effect badges, currency symbols).
 * - [ProgressBar] — `[████░░░░]` style fill bar (Wave 5refine refinement
 *   progress, Wave 5bet betting status, mana / cooldown previews).
 *
 * Sealed for exhaustive adapter dispatch.
 */
public sealed interface TooltipLine {

    /**
     * Plain text line. The adapter resolves [messageKey] via `IMessageRenderer`
     * and tints it with [color] (which defaults to [HudColor.WHITE]).
     */
    public data class Text(
        public val messageKey: MessageKey,
        public val color: HudColor = HudColor.WHITE,
    ) : TooltipLine

    /**
     * Embedded custom-font glyph. Reuses Wave 5j [HudGlyphRef] — the same
     * glyph registry that powers HUD overlays renders inline in tooltips.
     */
    public data class Image(public val textureRef: HudGlyphRef) : TooltipLine

    /**
     * Horizontal fill bar. [percent] is in `0.0..1.0` (clamped at `init`).
     * [fillColor] is the bar's filled portion; the unfilled portion is left
     * to the adapter's stylesheet (typically dark grey).
     */
    public data class ProgressBar(
        public val percent: Double,
        public val fillColor: HudColor,
    ) : TooltipLine {
        init {
            require(percent in 0.0..1.0) {
                "ProgressBar.percent must be in 0.0..1.0 (got $percent)"
            }
        }
    }
}
