package com.astarworks.astera.core.domain.hud.status

/**
 * Negative-space font (ADR-0097): private-use characters that move the pen by
 * a power of two pixels, forwards or backwards. They come from a `space`
 * provider in the font (no PNG needed), so any integer offset is a short run
 * of these characters.
 */
public object NegativeSpace {

    public const val MAX_EXPONENT: Int = 8
    public const val POSITIVE_BASE: Int = 0xF810
    public const val NEGATIVE_BASE: Int = 0xF830

    /** Every spacer character with its advance, for the generated `space` provider. */
    public val advances: Map<Char, Int> = buildMap {
        for (k in 0..MAX_EXPONENT) {
            put((POSITIVE_BASE + k).toChar(), 1 shl k)
            put((NEGATIVE_BASE + k).toChar(), -(1 shl k))
        }
    }

    public fun advanceOf(char: Char): Int? = advances[char]

    /** A string that moves the pen by exactly [px] pixels (empty for 0). */
    public fun spacer(px: Int): String {
        if (px == 0) return ""
        val base = if (px > 0) POSITIVE_BASE else NEGATIVE_BASE
        var left = kotlin.math.abs(px)
        val out = StringBuilder()
        val biggest = 1 shl MAX_EXPONENT
        while (left >= biggest) {
            out.append((base + MAX_EXPONENT).toChar())
            left -= biggest
        }
        for (k in MAX_EXPONENT - 1 downTo 0) {
            if (left and (1 shl k) != 0) out.append((base + k).toChar())
        }
        return out.toString()
    }
}
