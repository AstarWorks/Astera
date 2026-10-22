package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusEffectHudLayoutTest {
    @Test
    fun `effects sort by id and show ceiling seconds and a separate stack badge`() {
        val row = StatusEffectHudLayout.glyphs(listOf(
            StatusEffectHudLayout.Entry("regen", 20, 1),
            StatusEffectHudLayout.Entry("burn", 61, 12),
            StatusEffectHudLayout.Entry("wet", 0, 1),
        ))
        assertThat(row).startsWith(StatusEffectHudGlyphs.icon("burn").toString())
        assertThat(row).contains(StatusEffectHudGlyphs.digit(4).toString())
        assertThat(row).contains("" + StatusEffectHudGlyphs.stack(1) + StatusEffectHudGlyphs.stack(2))
        assertThat(row).doesNotContain(StatusEffectHudGlyphs.icon("wet").toString())
        assertThat(StatusEffectHudGlyphs.advance(row)).isEqualTo(10 + 8 + 3 + 10 + 4)
    }

    @Test
    fun `all effects plus cooldowns and ammo preserve total advance and the hearts anchor`() {
        val bar = AmmoReadoutLayout.appendTo(HpBarLayout.render(100.0, 200.0), AmmoReadoutLayout.render(9, 20))
        val cooldown = CooldownHudLayout.overlay(bar, listOf(CooldownHudLayout.Entry(SkillTrigger.RIGHT_CLICK, 61)))
        val result = StatusEffectHudLayout.overlay(cooldown, StatusEffectHudGlyphs.IDS.map {
            StatusEffectHudLayout.Entry(it, 100_000, 100)
        })
        assertThat(result.widthPx).isEqualTo(bar.widthPx)
        val advance = result.text.sumOf { c ->
            when {
                NegativeSpace.advanceOf(c) != null -> NegativeSpace.advanceOf(c)!!
                c.code in 0xE300..0xE33F -> StatusEffectHudGlyphs.advance(c)
                c.code in 0xE200..0xE2FF -> CooldownHudGlyphs.advance(c)
                else -> StatusHudGlyphs.advance(c)
            }
        }
        assertThat(advance).isEqualTo(bar.widthPx)
        val gap = StatusLineAnchor.gapPx(-91, result.widthPx, 37)
        assertThat(StatusLineAnchor.blockLeftPx(37, gap, result.widthPx)).isEqualTo(-91.0)
        assertThat(StatusEffectHudLayout.overlay(bar, emptyList())).isSameAs(bar)
    }
}
