package com.astarworks.astera.core.domain.hologram.transition

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.joml.Quaternionf
import org.junit.jupiter.api.Test
import kotlin.math.PI

class HologramRotationInterpolatorTest {

    private val tolerance = Offset.offset(1e-5f)

    @Test
    fun `slerp at factor 0 returns from quaternion`() {
        val from = Quaternionf().fromAxisAngleRad(1f, 0f, 0f, (PI / 4).toFloat())
        val to = Quaternionf().fromAxisAngleRad(1f, 0f, 0f, (PI / 2).toFloat())
        val result = HologramRotationInterpolator.slerp(from, to, 0.0)
        assertThat(result.x).isCloseTo(from.x, tolerance)
        assertThat(result.y).isCloseTo(from.y, tolerance)
        assertThat(result.z).isCloseTo(from.z, tolerance)
        assertThat(result.w).isCloseTo(from.w, tolerance)
    }

    @Test
    fun `slerp at factor 1 returns to quaternion`() {
        val from = Quaternionf()
        val to = Quaternionf().fromAxisAngleRad(0f, 1f, 0f, (PI / 2).toFloat())
        val result = HologramRotationInterpolator.slerp(from, to, 1.0)
        assertThat(result.x).isCloseTo(to.x, tolerance)
        assertThat(result.y).isCloseTo(to.y, tolerance)
        assertThat(result.z).isCloseTo(to.z, tolerance)
        assertThat(result.w).isCloseTo(to.w, tolerance)
    }

    @Test
    fun `slerp at factor 0_5 produces a quaternion midway between from and to`() {
        // Identity to 180 degrees around X axis.
        val from = Quaternionf()
        val to = Quaternionf().fromAxisAngleRad(1f, 0f, 0f, PI.toFloat())
        val mid = HologramRotationInterpolator.slerp(from, to, 0.5)
        // Halfway rotation = 90 degrees around X axis.
        val expected = Quaternionf().fromAxisAngleRad(1f, 0f, 0f, (PI / 2).toFloat())
        assertThat(mid.x).isCloseTo(expected.x, tolerance)
        assertThat(mid.y).isCloseTo(expected.y, tolerance)
        assertThat(mid.z).isCloseTo(expected.z, tolerance)
        assertThat(mid.w).isCloseTo(expected.w, tolerance)
    }

    @Test
    fun `slerp does not mutate from or to inputs`() {
        val from = Quaternionf()
        val to = Quaternionf().fromAxisAngleRad(0f, 0f, 1f, (PI / 2).toFloat())
        val fromCopy = Quaternionf(from)
        val toCopy = Quaternionf(to)
        HologramRotationInterpolator.slerp(from, to, 0.5)
        // Both inputs should be unchanged.
        assertThat(from.x).isEqualTo(fromCopy.x)
        assertThat(from.y).isEqualTo(fromCopy.y)
        assertThat(from.z).isEqualTo(fromCopy.z)
        assertThat(from.w).isEqualTo(fromCopy.w)
        assertThat(to.x).isEqualTo(toCopy.x)
        assertThat(to.y).isEqualTo(toCopy.y)
        assertThat(to.z).isEqualTo(toCopy.z)
        assertThat(to.w).isEqualTo(toCopy.w)
    }

    @Test
    fun `slerp returns a unit-magnitude quaternion for unit-magnitude inputs at any factor`() {
        val from = Quaternionf().fromAxisAngleRad(1f, 0f, 0f, (PI / 6).toFloat())
        val to = Quaternionf().fromAxisAngleRad(0f, 1f, 0f, (PI / 3).toFloat())
        for (factor in listOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            val mid = HologramRotationInterpolator.slerp(from, to, factor)
            val mag = Math.sqrt((mid.x * mid.x + mid.y * mid.y + mid.z * mid.z + mid.w * mid.w).toDouble())
            assertThat(mag).isCloseTo(1.0, Offset.offset(1e-5))
        }
    }
}
