package com.astarworks.astera.core.domain.hud.status

/** Procedural effect icons, seconds (white, below) and stack badges (gold, above). */
public object StatusEffectHudGlyphs {
    public const val CELL_WIDTH: Int = 9
    public const val CELL_HEIGHT: Int = 12
    public const val ASCENT: Int = CooldownHudGlyphs.ASCENT + 13
    public const val ICON_ADVANCE: Int = 10
    public const val DIGIT_ADVANCE: Int = 4

    /** Stable code point order; append new content IDs here. Every `content/status/` id plus the mechanic statuses (silence, immunity) must be here. */
    public val IDS: List<String> = listOf(
        "bleed", "burn", "charge", "dash", "evasive", "freeze", "poise", "poison",
        "regen", "reloading", "rupture", "shock", "sinking", "tremor", "wet", "silence", "immunity", "swift", "root",
    )
    public fun icon(id: String): Char = (0xE300 + IDS.indexOf(id).also { require(it >= 0) }).toChar()
    public fun digit(value: Int): Char = (0xE320 + value.also { require(it in 0..9) }).toChar()
    public fun stack(value: Int): Char = (0xE330 + value.also { require(it in 0..9) }).toChar()
    public val ATLAS_ROWS: List<String> = listOf(
        IDS.joinToString("") { icon(it).toString() },
        (0..9).joinToString("") { digit(it).toString() } + "\u0000".repeat(IDS.size - 10),
        (0..9).joinToString("") { stack(it).toString() } + "\u0000".repeat(IDS.size - 10),
    )
    public fun advance(char: Char): Int = when (char) {
        in icon(IDS.first())..icon(IDS.last()) -> ICON_ADVANCE
        in digit(0)..digit(9), in stack(0)..stack(9) -> DIGIT_ADVANCE
        else -> NegativeSpace.advanceOf(char) ?: error("not an effect glyph: $char")
    }
    public fun advance(text: String): Int = text.sumOf { advance(it) }
}
