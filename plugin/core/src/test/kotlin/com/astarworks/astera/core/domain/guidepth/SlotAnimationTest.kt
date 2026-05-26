package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.gui.SlotIndex
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class SlotAnimationTest {

    @Test
    fun `Fade accepts alpha bounds`() {
        val fade = SlotAnimation.Fade(0.0, 1.0, durationTicks = 20L, easing = Easing.LINEAR)
        assertThat(fade.fromAlpha).isEqualTo(0.0)
        assertThat(fade.toAlpha).isEqualTo(1.0)
    }

    @Test
    fun `Fade rejects alpha out of range`() {
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Fade(-0.1, 1.0, 20L, Easing.LINEAR)
        }
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Fade(0.0, 1.1, 20L, Easing.LINEAR)
        }
    }

    @Test
    fun `Fade rejects negative duration`() {
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Fade(0.0, 1.0, -1L, Easing.LINEAR)
        }
    }

    @Test
    fun `Slide accepts valid construction`() {
        val slide = SlotAnimation.Slide(
            fromSlot = SlotIndex(0),
            toSlot = SlotIndex(8),
            durationTicks = 10L,
            easing = Easing.EASE_OUT_QUAD,
        )
        assertThat(slide.fromSlot).isEqualTo(SlotIndex(0))
        assertThat(slide.toSlot).isEqualTo(SlotIndex(8))
    }

    @Test
    fun `Bounce requires positive amplitude`() {
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Bounce(amplitude = 0.0, durationTicks = 20L)
        }
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Bounce(amplitude = -1.0, durationTicks = 20L)
        }
    }

    @Test
    fun `Pulse requires scaleMax greater-equal scaleMin`() {
        // Equal is allowed (degenerate but valid — zero-amplitude pulse)
        SlotAnimation.Pulse(scaleMin = 1.0, scaleMax = 1.0, durationTicks = 20L)
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Pulse(scaleMin = 1.2, scaleMax = 0.8, durationTicks = 20L)
        }
    }

    @Test
    fun `Pulse requires positive scales`() {
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Pulse(scaleMin = 0.0, scaleMax = 1.0, durationTicks = 20L)
        }
        assertThatIllegalArgumentException().isThrownBy {
            SlotAnimation.Pulse(scaleMin = 1.0, scaleMax = 0.0, durationTicks = 20L)
        }
    }
}
