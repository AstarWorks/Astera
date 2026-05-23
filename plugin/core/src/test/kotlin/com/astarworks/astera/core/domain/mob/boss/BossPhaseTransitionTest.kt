package com.astarworks.astera.core.domain.mob.boss

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BossPhaseTransitionTest {

    @Test
    fun `HpThreshold valid construction`() {
        val t = BossPhaseTransition.HpThreshold(
            fromPhase = 0,
            toPhase = 1,
            hpFraction = 0.66,
        )
        assertThat(t.fromPhase).isEqualTo(0)
        assertThat(t.toPhase).isEqualTo(1)
        assertThat(t.hpFraction).isEqualTo(0.66)
    }

    @Test
    fun `HpThreshold rejects toPhase not greater than fromPhase`() {
        assertThatThrownBy {
            BossPhaseTransition.HpThreshold(fromPhase = 1, toPhase = 1, hpFraction = 0.5)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be > fromPhase")
    }

    @Test
    fun `HpThreshold rejects negative fromPhase`() {
        assertThatThrownBy {
            BossPhaseTransition.HpThreshold(fromPhase = -1, toPhase = 0, hpFraction = 0.5)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HpThreshold rejects hpFraction out of range`() {
        assertThatThrownBy {
            BossPhaseTransition.HpThreshold(fromPhase = 0, toPhase = 1, hpFraction = 1.5)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            BossPhaseTransition.HpThreshold(fromPhase = 0, toPhase = 1, hpFraction = -0.1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
