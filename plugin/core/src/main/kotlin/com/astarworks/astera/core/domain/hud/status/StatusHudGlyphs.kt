package com.astarworks.astera.core.domain.hud.status

/**
 * The glyph table of the status HUD (ADR-0182): which private-use code point
 * is which picture in `content/hud/status/atlas.png`, and how far each one
 * advances the pen.
 *
 * Single source of truth for three consumers: the runtime renderer
 * ([HpBarLayout]), the pack tool (`:tools:resource-pack` generates the atlas
 * and the font JSON from this table) and the tests that read the committed PNG
 * back and compare it with [advance].
 *
 * # Advance of a bitmap glyph
 *
 * Minecraft trims transparent columns off the right of a bitmap cell and
 * advances by `visibleWidth + 1`. Every glyph below therefore has an opaque
 * (or alpha-1 "marker") pixel in its last visible column, so [advance] is
 * exact and known without measuring text.
 */
public object StatusHudGlyphs {

    /** Atlas cell size in pixels (every glyph is drawn inside one cell). */
    public const val CELL_WIDTH: Int = 8
    public const val CELL_HEIGHT: Int = 9
    public const val ATLAS_COLUMNS: Int = 16

    /**
     * Distance in pixels from the action bar's baseline to the top of the
     * glyph row, negative = below. The vanilla action bar baseline sits about
     * 65 px above the bottom edge; hearts start 39 px above it, hence -26.
     * In-game tuning knob (ADR-0182 section Verification).
     */
    public const val ASCENT: Int = -26

    public const val BAR_SEGMENTS: Int = 20
    public const val COLUMNS_PER_SEGMENT: Int = 3
    public const val BAR_COLUMNS: Int = BAR_SEGMENTS * COLUMNS_PER_SEGMENT

    public const val CAP_LEFT: Char = ''
    public const val CAP_RIGHT: Char = ''
    public const val SEG_EMPTY: Char = ''
    public const val SEG_FULL: Char = ''
    public const val SEG_ONE: Char = ''
    public const val SEG_TWO: Char = ''
    public const val SEG_CRIT_FULL: Char = ''
    public const val SEG_CRIT_ONE: Char = ''
    public const val SEG_CRIT_TWO: Char = ''
    public const val HEART: Char = ''
    public const val SLASH: Char = ''
    public const val DIGIT_ZERO: Char = ''

    /** Not a glyph: a cell that stays empty in the atlas grid. */
    public const val NONE: Char = '\u0000'

    /** The atlas grid, row by row (`chars` of the bitmap font provider). */
    public val ATLAS_ROWS: List<String> = listOf(
        "$CAP_LEFT$CAP_RIGHT$SEG_EMPTY$SEG_FULL$SEG_ONE$SEG_TWO$SEG_CRIT_FULL$SEG_CRIT_ONE$SEG_CRIT_TWO$HEART$SLASH" +
            NONE.toString().repeat(5),
        (0..9).joinToString("") { digit(it).toString() } + NONE.toString().repeat(6),
    )

    public fun digit(value: Int): Char {
        require(value in 0..9) { "digit must be 0..9, was $value" }
        return DIGIT_ZERO + value
    }

    /** Pixels the pen moves after drawing [glyph] (an atlas glyph or a spacer). */
    public fun advance(glyph: Char): Int = when {
        glyph == CAP_LEFT || glyph == CAP_RIGHT -> 2
        glyph in SEG_EMPTY..SEG_CRIT_TWO -> COLUMNS_PER_SEGMENT + 1
        glyph == HEART -> 8
        glyph == SLASH || glyph in digit(0)..digit(9) -> 7
        else -> NegativeSpace.advanceOf(glyph) ?: error("not a status HUD glyph: U+%04X".format(glyph.code))
    }

    /** Total advance of [text] in pixels. */
    public fun advance(text: String): Int = text.sumOf { advance(it) }
}
