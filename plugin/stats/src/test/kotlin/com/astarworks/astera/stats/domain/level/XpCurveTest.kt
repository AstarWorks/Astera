package com.astarworks.astera.stats.domain.level

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XpCurveTest {

    @Test
    fun `thresholdToReach level 1 is 0`() {
        assertThat(XpCurve.thresholdToReach(1)).isEqualTo(0L)
    }

    @Test
    fun `thresholdToReach level 2 is 100`() {
        assertThat(XpCurve.thresholdToReach(2)).isEqualTo(100L)
    }

    @Test
    fun `thresholdToReach level 3 is 300`() {
        assertThat(XpCurve.thresholdToReach(3)).isEqualTo(300L)
    }

    @Test
    fun `thresholdToReach level 4 is 600`() {
        assertThat(XpCurve.thresholdToReach(4)).isEqualTo(600L)
    }

    @Test
    fun `thresholdToReach level 5 is 1000`() {
        assertThat(XpCurve.thresholdToReach(5)).isEqualTo(1000L)
    }

    @Test
    fun `levelFor 0 xp is level 1`() {
        assertThat(XpCurve.levelFor(0L)).isEqualTo(Level.ONE)
    }

    @Test
    fun `levelFor 99 xp is level 1`() {
        assertThat(XpCurve.levelFor(99L)).isEqualTo(Level.ONE)
    }

    @Test
    fun `levelFor 100 xp is level 2`() {
        assertThat(XpCurve.levelFor(100L)).isEqualTo(Level(2))
    }

    @Test
    fun `levelFor 299 xp is level 2`() {
        assertThat(XpCurve.levelFor(299L)).isEqualTo(Level(2))
    }

    @Test
    fun `levelFor 300 xp is level 3`() {
        assertThat(XpCurve.levelFor(300L)).isEqualTo(Level(3))
    }

    @Test
    fun `levelFor 599 xp is level 3`() {
        assertThat(XpCurve.levelFor(599L)).isEqualTo(Level(3))
    }

    @Test
    fun `levelFor 600 xp is level 4`() {
        assertThat(XpCurve.levelFor(600L)).isEqualTo(Level(4))
    }

    @Test
    fun `levelFor exact threshold always returns that level`() {
        for (level in 1..10) {
            val threshold = XpCurve.thresholdToReach(level)
            assertThat(XpCurve.levelFor(threshold))
                .describedAs("levelFor(thresholdToReach($level)) should be Level($level)")
                .isEqualTo(Level(level))
        }
    }

    @Test
    fun `levelFor one below threshold stays at previous level`() {
        for (level in 2..10) {
            val threshold = XpCurve.thresholdToReach(level)
            assertThat(XpCurve.levelFor(threshold - 1))
                .describedAs("levelFor(threshold($level) - 1) should be Level(${level - 1})")
                .isEqualTo(Level(level - 1))
        }
    }
}
