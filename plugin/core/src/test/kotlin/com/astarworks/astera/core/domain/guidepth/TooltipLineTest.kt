package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class TooltipLineTest {

    @Test
    fun `Text defaults to WHITE`() {
        val line = TooltipLine.Text(MessageKey("astera.tooltip.foo"))
        assertThat(line.color).isEqualTo(HudColor.WHITE)
    }

    @Test
    fun `Image carries HudGlyphRef`() {
        val ref = HudGlyphRef("currency/star-small")
        val line = TooltipLine.Image(ref)
        assertThat(line.textureRef).isEqualTo(ref)
    }

    @Test
    fun `ProgressBar clamps percent in init`() {
        TooltipLine.ProgressBar(percent = 0.0, fillColor = HudColor.RED)
        TooltipLine.ProgressBar(percent = 1.0, fillColor = HudColor.RED)
        assertThatIllegalArgumentException().isThrownBy {
            TooltipLine.ProgressBar(percent = -0.1, fillColor = HudColor.RED)
        }
        assertThatIllegalArgumentException().isThrownBy {
            TooltipLine.ProgressBar(percent = 1.1, fillColor = HudColor.RED)
        }
    }
}
