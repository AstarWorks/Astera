package com.astarworks.astera.domain.animation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class EasingTest {

    private val eps = Offset.offset(1e-9)

    @ParameterizedTest
    @EnumSource(Easing::class)
    fun `every easing maps t=0 to 0`(e: Easing) {
        assertThat(e.apply(0.0)).isCloseTo(0.0, eps)
    }

    @ParameterizedTest
    @EnumSource(Easing::class)
    fun `every easing maps t=1 to 1`(e: Easing) {
        assertThat(e.apply(1.0)).isCloseTo(1.0, eps)
    }

    @ParameterizedTest
    @EnumSource(Easing::class)
    fun `apply clamps to 0,1`(e: Easing) {
        // BACK and ELASTIC overshoot inside (0,1) — that's intentional — but inputs
        // outside (0,1) must be clamped before the curve is evaluated, producing the
        // same value as the endpoint.
        assertThat(e.apply(-5.0)).isCloseTo(e.apply(0.0), eps)
        assertThat(e.apply(5.0)).isCloseTo(e.apply(1.0), eps)
    }

    @Test
    fun `LINEAR is the identity`() {
        for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            assertThat(Easing.LINEAR.apply(t)).isCloseTo(t, eps)
        }
    }

    @Test
    fun `EASE_IN_QUAD at midpoint is 0_25`() {
        assertThat(Easing.EASE_IN_QUAD.apply(0.5)).isCloseTo(0.25, eps)
    }

    @Test
    fun `EASE_OUT_QUAD at midpoint is 0_75`() {
        assertThat(Easing.EASE_OUT_QUAD.apply(0.5)).isCloseTo(0.75, eps)
    }

    @Test
    fun `interpolate lerps endpoints`() {
        assertThat(Easing.LINEAR.interpolate(10.0, 20.0, 0.0)).isCloseTo(10.0, eps)
        assertThat(Easing.LINEAR.interpolate(10.0, 20.0, 1.0)).isCloseTo(20.0, eps)
        assertThat(Easing.LINEAR.interpolate(10.0, 20.0, 0.5)).isCloseTo(15.0, eps)
    }

    @Test
    fun `EASE_IN_BACK actually goes negative before reaching 0_3`() {
        // Sanity-check the overshooting curves: should dip below 0 in the early region.
        assertThat(Easing.EASE_IN_BACK.apply(0.2)).isLessThan(0.0)
    }

    @Test
    fun `EASE_OUT_BACK overshoots above 1 in the late region`() {
        assertThat(Easing.EASE_OUT_BACK.apply(0.8)).isGreaterThan(1.0)
    }
}
