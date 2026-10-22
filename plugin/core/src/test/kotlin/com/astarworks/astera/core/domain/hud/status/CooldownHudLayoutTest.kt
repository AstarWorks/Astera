package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CooldownHudLayoutTest {

    private fun entry(trigger: SkillTrigger, ticks: Long) = CooldownHudLayout.Entry(trigger, ticks)

    @Test
    fun `seconds round up and stay within two digits`() {
        assertThat(CooldownHudLayout.seconds(1)).isEqualTo(1)
        assertThat(CooldownHudLayout.seconds(20)).isEqualTo(1)
        assertThat(CooldownHudLayout.seconds(21)).isEqualTo(2)
        assertThat(CooldownHudLayout.seconds(60)).isEqualTo(3)
        assertThat(CooldownHudLayout.seconds(100_000)).isEqualTo(CooldownHudLayout.MAX_SECONDS)
    }

    @Test
    fun `a ready entry is the lit icon alone, a cooling one the dim icon plus its seconds`() {
        val ready = CooldownHudLayout.glyphs(listOf(entry(SkillTrigger.RIGHT_CLICK, 0)))
        assertThat(ready).isEqualTo(CooldownHudGlyphs.ready(SkillTrigger.RIGHT_CLICK).toString())

        val cooling = CooldownHudLayout.glyphs(listOf(entry(SkillTrigger.LEFT_CLICK, 130)))
        assertThat(cooling).isEqualTo(
            "" + CooldownHudGlyphs.cooling(SkillTrigger.LEFT_CLICK) + CooldownHudGlyphs.digit(7),
        )
    }

    @Test
    fun `entries are laid out in input order with a gap, and unsupported inputs are skipped`() {
        val row = CooldownHudLayout.glyphs(
            listOf(
                entry(SkillTrigger.DROP, 0),
                entry(SkillTrigger.HOLD, 100),
                entry(SkillTrigger.RIGHT_CLICK, 40),
            ),
        )
        assertThat(row).isEqualTo(
            "" + CooldownHudGlyphs.cooling(SkillTrigger.RIGHT_CLICK) + CooldownHudGlyphs.digit(2) +
                NegativeSpace.spacer(CooldownHudLayout.ENTRY_GAP_PX) + CooldownHudGlyphs.ready(SkillTrigger.DROP),
        )
        assertThat(CooldownHudGlyphs.advance(row)).isEqualTo(
            CooldownHudGlyphs.ICON_ADVANCE + CooldownHudGlyphs.DIGIT_ADVANCE + CooldownHudLayout.ENTRY_GAP_PX +
                CooldownHudGlyphs.ICON_ADVANCE,
        )
    }

    @Test
    fun `the overlay keeps the block's width so the anchoring does not move`() {
        val block = HpBarLayout.render(100.0, 200.0)
        val withRow = CooldownHudLayout.overlay(
            block,
            listOf(entry(SkillTrigger.LEFT_CLICK, 0), entry(SkillTrigger.RIGHT_CLICK, 65), entry(SkillTrigger.DROP, 600)),
        )
        assertThat(withRow.widthPx).isEqualTo(block.widthPx)
        assertThat(withRow.text).startsWith(block.text)
        // Net pen movement of the whole text is the block's width.
        assertThat(advanceOf(withRow.text)).isEqualTo(block.widthPx)
    }

    @Test
    fun `no entries leave the block untouched`() {
        val block = HpBarLayout.render(100.0, 200.0)
        assertThat(CooldownHudLayout.overlay(block, emptyList())).isSameAs(block)
    }

    @Test
    fun `the text fallback names the input, the seconds left or ready`() {
        val parts = CooldownHudLayout.textParts(
            listOf(entry(SkillTrigger.SNEAK_RIGHT, 45), entry(SkillTrigger.LEFT_CLICK, 0)),
        )
        assertThat(parts.map { it.messageKey.value }).containsExactly(
            "astera.hud.cooldown.lc.ready",
            "astera.hud.cooldown.sneak-rc.waiting",
        )
        assertThat(parts[1].placeholders).containsEntry("seconds", "3")
    }

    @Test
    fun `every supported input has a text key pair`() {
        for (trigger in CooldownHudGlyphs.TRIGGERS) {
            val parts = CooldownHudLayout.textParts(listOf(entry(trigger, 0), entry(trigger, 20)))
            assertThat(parts).hasSize(2)
        }
    }

    private fun advanceOf(text: String): Int = text.sumOf { char ->
        when {
            NegativeSpace.advanceOf(char) != null -> NegativeSpace.advanceOf(char)!!
            CooldownHudGlyphs.isIcon(char) || char in CooldownHudGlyphs.digit(0)..CooldownHudGlyphs.digit(9) ->
                CooldownHudGlyphs.advance(char)
            else -> StatusHudGlyphs.advance(char)
        }
    }
}
