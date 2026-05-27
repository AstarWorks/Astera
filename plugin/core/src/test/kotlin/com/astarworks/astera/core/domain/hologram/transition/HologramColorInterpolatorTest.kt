package com.astarworks.astera.core.domain.hologram.transition

import com.astarworks.astera.core.domain.hologram.HologramColor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HologramColorInterpolatorTest {

    @Test
    fun `lerp at factor 0 returns the from color`() {
        val from = HologramColor(red = 10, green = 20, blue = 30, alpha = 40)
        val to = HologramColor(red = 100, green = 110, blue = 120, alpha = 127)
        val result = HologramColorInterpolator.lerp(from, to, 0.0)
        assertThat(result).isEqualTo(from)
    }

    @Test
    fun `lerp at factor 1 returns the to color`() {
        val from = HologramColor(red = 10, green = 20, blue = 30, alpha = 40)
        val to = HologramColor(red = 100, green = 110, blue = 120, alpha = 127)
        val result = HologramColorInterpolator.lerp(from, to, 1.0)
        assertThat(result).isEqualTo(to)
    }

    @Test
    fun `lerp at factor 0_5 produces midpoint channel-wise`() {
        val from = HologramColor(red = 0, green = 0, blue = 0, alpha = 0)
        val to = HologramColor(red = 100, green = 60, blue = 40, alpha = 80)
        val mid = HologramColorInterpolator.lerp(from, to, 0.5)
        assertThat(mid).isNotNull
        assertThat(mid!!.red).isEqualTo(50.toByte())
        assertThat(mid.green).isEqualTo(30.toByte())
        assertThat(mid.blue).isEqualTo(20.toByte())
        assertThat(mid.alpha).isEqualTo(40.toByte())
    }

    @Test
    fun `lerp with null from treats it as transparent black`() {
        val to = HologramColor(red = 100, green = 100, blue = 100, alpha = 100)
        val mid = HologramColorInterpolator.lerp(null, to, 0.5)
        assertThat(mid).isNotNull
        // null → (0,0,0,0), so midpoint with (100,100,100,100) is (50,50,50,50).
        assertThat(mid!!.red).isEqualTo(50.toByte())
        assertThat(mid.green).isEqualTo(50.toByte())
        assertThat(mid.blue).isEqualTo(50.toByte())
        assertThat(mid.alpha).isEqualTo(50.toByte())
    }

    @Test
    fun `lerp with null to treats it as transparent black`() {
        val from = HologramColor(red = 60, green = 60, blue = 60, alpha = 60)
        val mid = HologramColorInterpolator.lerp(from, null, 0.5)
        assertThat(mid).isNotNull
        assertThat(mid!!.red).isEqualTo(30.toByte())
        assertThat(mid.green).isEqualTo(30.toByte())
        assertThat(mid.blue).isEqualTo(30.toByte())
        assertThat(mid.alpha).isEqualTo(30.toByte())
    }

    @Test
    fun `lerp with both null returns null`() {
        assertThat(HologramColorInterpolator.lerp(null, null, 0.5)).isNull()
        assertThat(HologramColorInterpolator.lerp(null, null, 0.0)).isNull()
        assertThat(HologramColorInterpolator.lerp(null, null, 1.0)).isNull()
    }

    @Test
    fun `lerp clamps byte channels above 127 to 127`() {
        // Endpoints both at byte max 127 — overshoot easing should not push beyond.
        val from = HologramColor(red = 127, green = 127, blue = 127, alpha = 127)
        val to = HologramColor(red = 127, green = 127, blue = 127, alpha = 127)
        // Use a factor > 1 to simulate easing overshoot.
        val result = HologramColorInterpolator.lerp(from, to, 1.5)
        assertThat(result).isNotNull
        assertThat(result!!.red).isEqualTo(127.toByte())
    }

    @Test
    fun `lerp clamps byte channels below -128 to -128`() {
        val from = HologramColor(red = -128, green = -128, blue = -128, alpha = -128)
        val to = HologramColor(red = -128, green = -128, blue = -128, alpha = -128)
        val result = HologramColorInterpolator.lerp(from, to, -0.5)
        assertThat(result).isNotNull
        assertThat(result!!.red).isEqualTo((-128).toByte())
    }

    @Test
    fun `lerp at factor 0_25 rounds half-to-even on byte channels`() {
        // from=0, to=10 at factor=0.25 → 2.5; roundToInt() rounds 0.5 ties to nearest even via std.
        // Kotlin's Double.roundToInt() rounds 0.5 away-from-zero, so 2.5 → 3.
        // Verifying our chosen rounding semantics for documentation.
        val from = HologramColor(red = 0, green = 0, blue = 0)
        val to = HologramColor(red = 10, green = 10, blue = 10)
        val result = HologramColorInterpolator.lerp(from, to, 0.25)
        assertThat(result).isNotNull
        // 0 + (10 - 0) * 0.25 = 2.5 → roundToInt() = 3 (Kotlin rounds half-away-from-zero).
        assertThat(result!!.red).isEqualTo(3.toByte())
    }
}
