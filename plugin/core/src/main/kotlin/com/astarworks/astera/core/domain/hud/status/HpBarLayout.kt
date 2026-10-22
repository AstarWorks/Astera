package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.hud.HudComponent
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure model of the HP bar (ADR-0182): exact HP from the Astera container in,
 * a glyph string with a known width out.
 *
 * ```
 *  [heart][capL][ 20 segments x 3 columns ][capR]      <- 8 + 84 px
 *                  "123/200" centred on the bar        <- digits drawn over it
 * ```
 *
 * The 60 bar columns give 1/60 resolution (a full bar means HP == max; any HP
 * above zero shows at least one column). At or below 25 % the red turns to
 * the brighter critical set.
 */
public object HpBarLayout {

    /** Left edge of the vanilla heart row, relative to the screen centre. */
    public const val HEARTS_LEFT_PX: Int = -91

    public const val CRITICAL_RATIO: Double = 0.25

    private val barWidth: Int =
        StatusHudGlyphs.advance(StatusHudGlyphs.CAP_LEFT) +
            StatusHudGlyphs.BAR_SEGMENTS * (StatusHudGlyphs.COLUMNS_PER_SEGMENT + 1) +
            StatusHudGlyphs.advance(StatusHudGlyphs.CAP_RIGHT)

    /** Width of the whole block: heart + bar. */
    public val blockWidthPx: Int = StatusHudGlyphs.advance(StatusHudGlyphs.HEART) + barWidth

    /** Lit columns out of [StatusHudGlyphs.BAR_COLUMNS] for [hp] of [maxHp]. */
    public fun litColumns(hp: Double, maxHp: Double): Int {
        if (maxHp <= 0.0 || hp <= 0.0) return 0
        if (hp >= maxHp) return StatusHudGlyphs.BAR_COLUMNS
        val raw = (hp / maxHp * StatusHudGlyphs.BAR_COLUMNS).roundToInt()
        return raw.coerceIn(1, StatusHudGlyphs.BAR_COLUMNS - 1)
    }

    /** `cur/max` as shown on the bar (HP is rounded up so a living player never reads 0). */
    public fun numbers(hp: Double, maxHp: Double): String {
        val max = maxHp.roundToInt().coerceAtLeast(1)
        val cur = ceil(hp).toInt().coerceIn(0, max)
        return "$cur/$max"
    }

    public fun render(hp: Double, maxHp: Double): HudComponent.FontText {
        val lit = litColumns(hp, maxHp)
        val critical = maxHp > 0.0 && hp / maxHp <= CRITICAL_RATIO
        val out = StringBuilder()
        out.append(StatusHudGlyphs.HEART)
        out.append(StatusHudGlyphs.CAP_LEFT)
        for (segment in 0 until StatusHudGlyphs.BAR_SEGMENTS) {
            val columns = (lit - segment * StatusHudGlyphs.COLUMNS_PER_SEGMENT)
                .coerceIn(0, StatusHudGlyphs.COLUMNS_PER_SEGMENT)
            out.append(segmentGlyph(columns, critical))
        }
        out.append(StatusHudGlyphs.CAP_RIGHT)

        // Back to the bar's start, then the digits centred on the bar.
        val full = numbers(hp, maxHp)
        val text = if (StatusHudGlyphs.advance(glyphsOf(full)) <= barWidth) full else full.substringBefore('/')
        val glyphs = glyphsOf(text)
        val textWidth = StatusHudGlyphs.advance(glyphs)
        val offset = ((barWidth - textWidth) / 2).coerceAtLeast(0)
        out.append(NegativeSpace.spacer(-barWidth + offset))
        out.append(glyphs)
        out.append(NegativeSpace.spacer(barWidth - offset - textWidth))

        return HudComponent.FontText(out.toString(), blockWidthPx)
    }

    private fun glyphsOf(text: String): String =
        text.map { if (it == '/') StatusHudGlyphs.SLASH else StatusHudGlyphs.digit(it - '0') }.joinToString("")

    private fun segmentGlyph(columns: Int, critical: Boolean): Char = when (columns) {
        0 -> StatusHudGlyphs.SEG_EMPTY
        1 -> if (critical) StatusHudGlyphs.SEG_CRIT_ONE else StatusHudGlyphs.SEG_ONE
        2 -> if (critical) StatusHudGlyphs.SEG_CRIT_TWO else StatusHudGlyphs.SEG_TWO
        else -> if (critical) StatusHudGlyphs.SEG_CRIT_FULL else StatusHudGlyphs.SEG_FULL
    }
}
