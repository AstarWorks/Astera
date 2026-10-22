package com.astarworks.astera.core.domain.hud.status

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HpBarLayoutTest {

    private val glyphs = StatusHudGlyphs

    /** The text with spacers removed: the drawn glyphs, in order. */
    private fun drawn(text: String): String = text.filter { NegativeSpace.advanceOf(it) == null }

    private fun bar(text: String): String =
        drawn(text).substring(drawn(text).indexOf(glyphs.CAP_LEFT) + 1, drawn(text).indexOf(glyphs.CAP_RIGHT))

    private fun numbersOf(text: String): String = drawn(text).substringAfter(glyphs.CAP_RIGHT)
        .map { if (it == glyphs.SLASH) '/' else '0' + (it - glyphs.DIGIT_ZERO) }.joinToString("")

    @Test
    fun `the block is heart plus bar and its string advances exactly that far`() {
        val block = HpBarLayout.render(150.0, 200.0)

        assertThat(block.widthPx).isEqualTo(HpBarLayout.blockWidthPx).isEqualTo(8 + 2 + 80 + 2)
        // The declared width is the real pen travel: glyph advances + spacers.
        assertThat(glyphs.advance(block.text)).isEqualTo(block.widthPx)
    }

    @Test
    fun `a full bar has every segment full and the numbers read max over max`() {
        val block = HpBarLayout.render(200.0, 200.0)

        assertThat(bar(block.text)).isEqualTo(glyphs.SEG_FULL.toString().repeat(20))
        assertThat(numbersOf(block.text)).isEqualTo("200/200")
    }

    @Test
    fun `half HP lights exactly half the segments`() {
        val block = HpBarLayout.render(100.0, 200.0)

        assertThat(bar(block.text)).isEqualTo(glyphs.SEG_FULL.toString().repeat(10) + glyphs.SEG_EMPTY.toString().repeat(10))
        assertThat(numbersOf(block.text)).isEqualTo("100/200")
    }

    @Test
    fun `a partly lit segment uses the one-column and two-column glyphs`() {
        // 61 of 200 HP -> round(61/200 * 60) = 18 columns = 6 full segments.
        assertThat(HpBarLayout.litColumns(61.0, 200.0)).isEqualTo(18)
        // 20.5 of 40: 30.75 -> 31 columns = 10 full segments + 1 column.
        val block = HpBarLayout.render(20.5, 40.0)
        assertThat(bar(block.text)).isEqualTo(
            glyphs.SEG_FULL.toString().repeat(10) + glyphs.SEG_ONE + glyphs.SEG_EMPTY.toString().repeat(9),
        )
        assertThat(HpBarLayout.litColumns(21.4, 40.0)).isEqualTo(32) // 32.1 -> 10 full segments + 2 columns
        assertThat(bar(HpBarLayout.render(21.4, 40.0).text)).contains(glyphs.SEG_TWO.toString())
    }

    @ParameterizedTest(name = "hp {0} of {1}: {2} columns")
    @CsvSource(
        "0, 200, 0",
        "0.01, 200, 1", // alive means at least one column
        "199.9, 200, 59", // not full until HP == max
        "200, 200, 60",
        "250, 200, 60", // over max clamps
        "-5, 200, 0",
        "10, 0, 0", // degenerate max
    )
    fun `lit columns clamp and never show a living player as empty or a hurt one as full`(hp: Double, max: Double, columns: Int) {
        assertThat(HpBarLayout.litColumns(hp, max)).isEqualTo(columns)
    }

    @Test
    fun `at or below a quarter the bar switches to the critical glyphs`() {
        assertThat(bar(HpBarLayout.render(50.0, 200.0).text)).contains(glyphs.SEG_CRIT_FULL.toString()).doesNotContain(glyphs.SEG_FULL.toString())
        assertThat(bar(HpBarLayout.render(51.0, 200.0).text)).contains(glyphs.SEG_FULL.toString()).doesNotContain(glyphs.SEG_CRIT_FULL.toString())
    }

    @Test
    fun `HP is shown rounded up so a living player never reads zero, and clamped to max`() {
        assertThat(HpBarLayout.numbers(0.2, 100.0)).isEqualTo("1/100")
        assertThat(HpBarLayout.numbers(0.0, 100.0)).isEqualTo("0/100")
        assertThat(HpBarLayout.numbers(120.0, 100.0)).isEqualTo("100/100")
        assertThat(HpBarLayout.numbers(37.0, 100.4)).isEqualTo("37/100")
    }

    @Test
    fun `digits are centred on the bar and the pen ends where the bar ends`() {
        val block = HpBarLayout.render(37.0, 100.0)
        // After the right cap the text walks back, draws the digits and returns to the bar's end.
        val afterCap = block.text.substringAfter(glyphs.CAP_RIGHT)
        var pen = 0
        var lowest = Int.MAX_VALUE
        for (c in afterCap) {
            pen += glyphs.advance(c)
            lowest = minOf(lowest, pen)
        }
        assertThat(pen).isZero()
        assertThat(lowest).isLessThan(0)
        // "37/100" = 6 glyphs x 7 px = 42 px centred in 84: starts 21 px in from the bar's left.
        val backToBarStart = afterCap.takeWhile { NegativeSpace.advanceOf(it) != null }
        assertThat(glyphs.advance(backToBarStart)).isEqualTo(-84 + 21)
    }

    @Test
    fun `numbers too wide for the bar fall back to the current value only`() {
        val block = HpBarLayout.render(123456.0, 234567.0)
        assertThat(numbersOf(block.text)).isEqualTo("123456")
        assertThat(glyphs.advance(block.text)).isEqualTo(block.widthPx)
    }

    @Test
    fun `negative space is exact for any offset, positive or negative`() {
        for (px in listOf(0, 1, 2, 3, 7, 8, 100, 255, 256, 257, 511, 512, 1000, -1, -5, -91, -182, -256, -300, -1000)) {
            assertThat(glyphs.advance(NegativeSpace.spacer(px))).`as`("spacer($px)").isEqualTo(px)
        }
    }

    @Test
    fun `anchoring puts the block's left edge on the requested pixel whatever the readout width`() {
        for (readout in listOf(0, 20, 57, 130)) {
            val gap = StatusLineAnchor.gapPx(HpBarLayout.HEARTS_LEFT_PX, HpBarLayout.blockWidthPx, readout)
            assertThat(StatusLineAnchor.blockLeftPx(readout, gap, HpBarLayout.blockWidthPx))
                .`as`("readout $readout px")
                .isEqualTo(HpBarLayout.HEARTS_LEFT_PX.toDouble())
        }
    }

    @Test
    fun `text width estimate follows the vanilla ASCII widths and counts kana as full width`() {
        assertThat(MinecraftTextWidth.estimate("")).isZero()
        assertThat(MinecraftTextWidth.estimate("Cooldown")).isEqualTo(7 * 6 + 3) // only the l is narrow
        assertThat(MinecraftTextWidth.estimate("a b")).isEqualTo(6 + 4 + 6)
        assertThat(MinecraftTextWidth.estimate("\u30af\u30fc\u30eb")).isEqualTo(27)
    }
}
