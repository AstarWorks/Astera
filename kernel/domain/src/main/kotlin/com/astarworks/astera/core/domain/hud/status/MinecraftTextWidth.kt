package com.astarworks.astera.core.domain.hud.status

/**
 * Estimated pixel width of ordinary chat text in the default font. Only used
 * to cancel the action bar's centring around the HP glyph block, where an
 * error of N pixels moves the block by N / 2 (ADR-0182). ASCII widths are the
 * vanilla ones; everything else (kana, kanji) is estimated as a full-width
 * glyph.
 */
public object MinecraftTextWidth {

    private const val DEFAULT: Int = 6
    private const val FULL_WIDTH: Int = 9

    private val narrow: Map<Char, Int> = buildMap {
        for (c in "!.,:;i|") put(c, 2)
        for (c in "'l`") put(c, 3)
        for (c in "I[]t ") put(c, 4)
        for (c in "fk<>(){}*\"") put(c, 5)
        for (c in "@~") put(c, 7)
    }

    public fun estimate(text: String): Int = text.sumOf { char ->
        when {
            char.code < 0x80 -> narrow[char] ?: DEFAULT
            char.code in 0xFF61..0xFF9F -> DEFAULT // half-width katakana
            else -> FULL_WIDTH
        }
    }
}
