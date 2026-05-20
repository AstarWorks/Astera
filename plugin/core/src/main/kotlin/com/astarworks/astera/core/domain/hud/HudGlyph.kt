package com.astarworks.astera.core.domain.hud

/**
 * Custom-font glyph metadata (Wave 5j, ADR-0097).
 *
 * Registered in [com.astarworks.astera.core.application.port.outbound.ICustomFontRegistry]
 * and resolved at render time. The Wave 6b adapter generates a Minecraft
 * resource pack's `assets/astera/font/default.json` from the registry,
 * one provider entry per glyph keyed by [unicodeChar].
 *
 * @property textureRef stable id (matches [HudComponent.Glyph.textureRef]).
 * @property unicodeChar Private Use Area codepoint (U+E000..U+F8FF) the
 *   glyph is mapped to in `font.json` — callers never see this directly,
 *   the renderer uses it as the actual character emitted to the player.
 *   The PUA bounds are strict: U+F900..U+FFFF is the *CJK Compatibility
 *   Ideographs* block, NOT the BMP PUA, and MUST be rejected.
 * @property widthPx logical width for layout calculations (negative-space
 *   spacers cancel this if needed).
 * @property heightPx glyph image height (for ascent-aware vertical align).
 * @property ascent vertical offset (Minecraft `font.json` `ascent` field).
 */
public data class HudGlyph(
    public val textureRef: HudGlyphRef,
    public val unicodeChar: Char,
    public val widthPx: Int,
    public val heightPx: Int,
    public val ascent: Int = 0,
) {
    init {
        require(unicodeChar.code in PUA_START..PUA_END) {
            "HudGlyph.unicodeChar must be in BMP PUA (U+E000..U+F8FF), was U+${
                unicodeChar.code.toString(16).uppercase().padStart(4, '0')
            }"
        }
        require(widthPx in 1..256) { "HudGlyph.widthPx must be 1..256, was $widthPx" }
        require(heightPx in 1..256) { "HudGlyph.heightPx must be 1..256, was $heightPx" }
    }

    public companion object {
        /** Start of the BMP Private Use Area (`U+E000`). */
        public const val PUA_START: Int = 0xE000

        /** End of the BMP Private Use Area (`U+F8FF`). */
        public const val PUA_END: Int = 0xF8FF

        /**
         * Lowest PUA codepoint not present in [used], or null if the PUA
         * is fully exhausted. Wave 6b font.json generation uses this to
         * deterministically allocate codepoints per glyph.
         *
         * Iteration is in ascending order so registration order doesn't
         * affect the chosen codepoint when the same `used` set is supplied.
         */
        public fun nextAvailableUnicodeChar(used: Set<Char>): Char? {
            for (cp in PUA_START..PUA_END) {
                val c = cp.toChar()
                if (c !in used) return c
            }
            return null
        }
    }
}
