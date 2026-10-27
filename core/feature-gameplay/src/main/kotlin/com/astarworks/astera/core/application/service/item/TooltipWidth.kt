package com.astarworks.astera.core.application.service.item

/**
 * Estimated pixel width of a plain tooltip line in the default font (docs/contributing/item-lore-style.md §5).
 * Full-width characters (ja) are 8 px, everything else about 6 px, so the card limit of
 * [MAX_PX] means "about 32 full-width or 40 half-width characters".
 */
public object TooltipWidth {
    public const val MAX_PX: Int = 256
    private const val FULL_PX = 8
    private const val HALF_PX = 6

    public fun pixels(text: String): Int = text.sumOf { widthOf(it) }

    /** [text] cut with `...` so it fits [maxPx]; unchanged when it already fits. */
    public fun fit(text: String, maxPx: Int = MAX_PX): String {
        if (pixels(text) <= maxPx) return text
        val ellipsis = "..."
        var used = pixels(ellipsis)
        val out = StringBuilder()
        for (c in text) {
            val w = widthOf(c)
            if (used + w > maxPx) break
            out.append(c)
            used += w
        }
        return out.toString().trimEnd() + ellipsis
    }

    private fun widthOf(c: Char): Int = if (isFullWidth(c)) FULL_PX else HALF_PX

    private fun isFullWidth(c: Char): Boolean = c.code >= 0x2E80 && c.code !in 0xFF61..0xFF9F
}
