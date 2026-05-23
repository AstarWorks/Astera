package com.astarworks.astera.stats.domain.progression

import com.astarworks.astera.core.domain.progression.SkillXpCurve
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Wave 5multi-skill (ADR-0101) — XP curve invariants + level lookup.
 */
class SkillXpCurveTest {

    @Test
    fun `levelFor returns 1 at xp 0`() {
        val curve = SkillXpCurve.exponential(base = 100L, factor = 1.2, maxLevel = 10)
        assertThat(curve.levelFor(0L)).isEqualTo(1)
    }

    @Test
    fun `levelFor bumps level at each threshold`() {
        val curve = SkillXpCurve(thresholds = listOf(100L, 250L, 500L))
        assertThat(curve.levelFor(0L)).isEqualTo(1)
        assertThat(curve.levelFor(99L)).isEqualTo(1)
        assertThat(curve.levelFor(100L)).isEqualTo(2)
        assertThat(curve.levelFor(249L)).isEqualTo(2)
        assertThat(curve.levelFor(250L)).isEqualTo(3)
        assertThat(curve.levelFor(500L)).isEqualTo(4)
        assertThat(curve.levelFor(1_000_000L)).isEqualTo(4) // max level cap
    }

    @Test
    fun `maxLevel is thresholds size plus one`() {
        val curve = SkillXpCurve(thresholds = listOf(100L, 250L, 500L))
        assertThat(curve.maxLevel).isEqualTo(4)
    }

    @Test
    fun `thresholdFor returns 0 for level 1`() {
        val curve = SkillXpCurve(thresholds = listOf(100L, 250L))
        assertThat(curve.thresholdFor(1)).isEqualTo(0L)
        assertThat(curve.thresholdFor(2)).isEqualTo(100L)
        assertThat(curve.thresholdFor(3)).isEqualTo(250L)
    }

    @Test
    fun `thresholdFor above maxLevel returns Long MAX_VALUE`() {
        val curve = SkillXpCurve(thresholds = listOf(100L))
        assertThat(curve.thresholdFor(99)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `exponential factory produces strictly increasing thresholds`() {
        val curve = SkillXpCurve.exponential(base = 100L, factor = 1.12, maxLevel = 100)
        assertThat(curve.thresholds).isSortedAccordingTo(Comparator.naturalOrder())
        for (i in 1 until curve.thresholds.size) {
            assertThat(curve.thresholds[i]).isGreaterThan(curve.thresholds[i - 1])
        }
    }

    @Test
    fun `requires non-empty thresholds`() {
        assertThatThrownBy { SkillXpCurve(thresholds = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requires monotonically increasing thresholds`() {
        assertThatThrownBy { SkillXpCurve(thresholds = listOf(100L, 50L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SkillXpCurve(thresholds = listOf(100L, 100L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
