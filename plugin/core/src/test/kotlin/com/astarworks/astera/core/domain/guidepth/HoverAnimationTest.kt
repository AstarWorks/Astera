package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.animation.Easing
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class HoverAnimationTest {

    @Test
    fun `Highlight accepts alpha bounds`() {
        HoverAnimation.Highlight(overlayColorArgb = 0xFFFFFFFF.toInt(), overlayAlpha = 0.25, durationTicks = 4L)
    }

    @Test
    fun `Highlight rejects alpha out of range`() {
        assertThatIllegalArgumentException().isThrownBy {
            HoverAnimation.Highlight(0, overlayAlpha = -0.1, durationTicks = 4L)
        }
        assertThatIllegalArgumentException().isThrownBy {
            HoverAnimation.Highlight(0, overlayAlpha = 1.1, durationTicks = 4L)
        }
    }

    @Test
    fun `Scale requires peakScale above 1`() {
        HoverAnimation.Scale(peakScale = 1.1, durationTicks = 4L, easing = Easing.EASE_OUT_QUAD)
        assertThatIllegalArgumentException().isThrownBy {
            HoverAnimation.Scale(peakScale = 1.0, durationTicks = 4L, easing = Easing.LINEAR)
        }
        assertThatIllegalArgumentException().isThrownBy {
            HoverAnimation.Scale(peakScale = 0.5, durationTicks = 4L, easing = Easing.LINEAR)
        }
    }
}
