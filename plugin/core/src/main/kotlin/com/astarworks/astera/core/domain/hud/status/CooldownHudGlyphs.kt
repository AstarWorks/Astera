package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.skill.SkillTrigger

/**
 * Glyph table of the per-binding cooldown icons (ADR-0195), the sibling of
 * [StatusHudGlyphs] for `content/hud/cooldown/atlas.png`.
 *
 * One atlas, one font provider, drawn one row above the HP bar (the vanilla armor
 * row, which the pack hides). Every glyph has a visible pixel in its last
 * column, so [advance] is exact (icons: 8 visible + 1, digits: 6 visible + 1).
 */
public object CooldownHudGlyphs {

    public const val CELL_WIDTH: Int = 9
    public const val CELL_HEIGHT: Int = 9
    public const val ATLAS_COLUMNS: Int = 16

    /** One 10 px row above [StatusHudGlyphs.ASCENT] (positive = higher on screen). In-game tuning knob. */
    public const val ASCENT: Int = StatusHudGlyphs.ASCENT + 10

    /** The inputs that get an icon, in display order. */
    public val TRIGGERS: List<SkillTrigger> = listOf(
        SkillTrigger.LEFT_CLICK,
        SkillTrigger.RIGHT_CLICK,
        SkillTrigger.SNEAK_LEFT,
        SkillTrigger.SNEAK_RIGHT,
        SkillTrigger.DROP,
    )

    public const val ICON_ADVANCE: Int = 9
    public const val DIGIT_ADVANCE: Int = 7

    private const val READY_BASE: Int = 0xE200
    private const val COOLING_BASE: Int = 0xE208
    private const val DIGIT_BASE: Int = 0xE210

    /** Not a glyph: a cell that stays empty in the atlas grid. */
    public const val NONE: Char = '\u0000'

    public fun supports(trigger: SkillTrigger): Boolean = trigger in TRIGGERS

    /** Icon of [trigger] in its lit (off cooldown) form. */
    public fun ready(trigger: SkillTrigger): Char = (READY_BASE + indexOf(trigger)).toChar()

    /** Icon of [trigger] in its dimmed (cooling down) form. */
    public fun cooling(trigger: SkillTrigger): Char = (COOLING_BASE + indexOf(trigger)).toChar()

    public fun digit(value: Int): Char {
        require(value in 0..9) { "digit must be 0..9, was $value" }
        return (DIGIT_BASE + value).toChar()
    }

    public fun indexOf(trigger: SkillTrigger): Int {
        val index = TRIGGERS.indexOf(trigger)
        require(index >= 0) { "no cooldown icon for $trigger" }
        return index
    }

    /** Rows of the bitmap provider (`chars`): ready icons, cooling icons, digits. */
    public val ATLAS_ROWS: List<String> = listOf(
        TRIGGERS.joinToString("") { ready(it).toString() } + NONE.toString().repeat(3) +
            TRIGGERS.joinToString("") { cooling(it).toString() } + NONE.toString().repeat(3),
        (0..9).joinToString("") { digit(it).toString() } + NONE.toString().repeat(6),
    )

    public fun isIcon(glyph: Char): Boolean =
        glyph.code in READY_BASE until READY_BASE + TRIGGERS.size ||
            glyph.code in COOLING_BASE until COOLING_BASE + TRIGGERS.size

    /** Pixels the pen moves after drawing [glyph] (an atlas glyph or a spacer). */
    public fun advance(glyph: Char): Int = when {
        isIcon(glyph) -> ICON_ADVANCE
        glyph in digit(0)..digit(9) -> DIGIT_ADVANCE
        else -> NegativeSpace.advanceOf(glyph) ?: error("not a cooldown HUD glyph: U+%04X".format(glyph.code))
    }

    public fun advance(text: String): Int = text.sumOf { advance(it) }
}
