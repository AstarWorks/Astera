package com.astarworks.astera.e2e

/**
 * Decodes the HP status line exactly as a client receives it on the action bar. With the resource pack loaded the
 * line is a glyph bar whose `cur/max` digits are font glyphs (HpBarLayout / StatusHudGlyphs: digits U+E110.., slash
 * U+E10A); without the pack it is the plain `HP cur/max` text. Lines that carry neither (tutorial hints, cast
 * feedback, other toasts) parse to null.
 */
data class HpReading(val current: Int, val max: Int, val glyphs: Boolean) {
    companion object {
        private const val DIGIT_ZERO = 0xE110
        private val GLYPH = Regex("([-]+)([-]+)")
        private val TEXT = Regex("HP\\s*(\\d+)\\s*/\\s*(\\d+)")

        private fun glyphNumber(run: String): Int = run.fold(0) { acc, c -> acc * 10 + (c.code - DIGIT_ZERO) }

        fun parse(text: String): HpReading? {
            GLYPH.find(text)?.let { return HpReading(glyphNumber(it.groupValues[1]), glyphNumber(it.groupValues[2]), glyphs = true) }
            TEXT.find(text)?.let { return HpReading(it.groupValues[1].toInt(), it.groupValues[2].toInt(), glyphs = false) }
            return null
        }
    }
}

/** HP readings among the action bars [Bot] received after index [from] (all of them by default), oldest first. */
fun Bot.hpReadings(from: Int = 0): List<HpReading> = actionBars.drop(from).mapNotNull { HpReading.parse(it.text) }

/** Lower-cases and strips everything but letters and digits, so `astera:weapons/gun-shot` matches however the protocol library prints a sound. */
fun soundKey(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
