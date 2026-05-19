package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import kotlin.math.PI

class QuaternionTest {

    private val eps = Offset.offset(1e-5f)

    @Test
    fun `IDENTITY composed with any quaternion is that quaternion`() {
        val q = Quaternion(0.1f, 0.2f, 0.3f, 0.4f).normalized()
        val left = Quaternion.IDENTITY.multiply(q)
        val right = q.multiply(Quaternion.IDENTITY)
        assertThat(left.x).isEqualTo(q.x, eps)
        assertThat(left.y).isEqualTo(q.y, eps)
        assertThat(left.z).isEqualTo(q.z, eps)
        assertThat(left.w).isEqualTo(q.w, eps)
        assertThat(right.x).isEqualTo(q.x, eps)
        assertThat(right.y).isEqualTo(q.y, eps)
        assertThat(right.z).isEqualTo(q.z, eps)
        assertThat(right.w).isEqualTo(q.w, eps)
    }

    @Test
    fun `fromAxisAngle around X axis at 90 degrees produces expected quaternion`() {
        // 90° around X = (sin(45°), 0, 0, cos(45°)) = (0.7071..., 0, 0, 0.7071...)
        val q = Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), (PI / 2).toFloat())
        val sqrt2over2 = 0.70710677f
        assertThat(q.x).isEqualTo(sqrt2over2, eps)
        assertThat(q.y).isEqualTo(0f, eps)
        assertThat(q.z).isEqualTo(0f, eps)
        assertThat(q.w).isEqualTo(sqrt2over2, eps)
        assertThat(q.length()).isEqualTo(1f, eps)
    }

    @Test
    fun `fromAxisAngle around Y axis at 180 degrees produces (0, 1, 0, 0)`() {
        val q = Quaternion.fromAxisAngle(Vec3(0.0, 1.0, 0.0), PI.toFloat())
        assertThat(q.x).isEqualTo(0f, eps)
        assertThat(q.y).isEqualTo(1f, eps)
        assertThat(q.z).isEqualTo(0f, eps)
        assertThat(q.w).isEqualTo(0f, eps)
    }

    @Test
    fun `fromAxisAngle around Z axis at 90 degrees produces unit quaternion`() {
        val q = Quaternion.fromAxisAngle(Vec3(0.0, 0.0, 1.0), (PI / 2).toFloat())
        val sqrt2over2 = 0.70710677f
        assertThat(q.x).isEqualTo(0f, eps)
        assertThat(q.y).isEqualTo(0f, eps)
        assertThat(q.z).isEqualTo(sqrt2over2, eps)
        assertThat(q.w).isEqualTo(sqrt2over2, eps)
    }

    @Test
    fun `multiply is non-commutative for distinct axis rotations`() {
        // 90° around X, then 90° around Y, should differ from 90° Y then 90° X
        val qx = Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), (PI / 2).toFloat())
        val qy = Quaternion.fromAxisAngle(Vec3(0.0, 1.0, 0.0), (PI / 2).toFloat())
        val xy = qx.multiply(qy)
        val yx = qy.multiply(qx)
        // At least one component must differ — these compose to different rotations
        val diff = (xy.x - yx.x) * (xy.x - yx.x) +
            (xy.y - yx.y) * (xy.y - yx.y) +
            (xy.z - yx.z) * (xy.z - yx.z) +
            (xy.w - yx.w) * (xy.w - yx.w)
        assertThat(diff).isGreaterThan(0.01f)
    }

    @Test
    fun `normalized of arbitrary non-unit quaternion has length 1`() {
        val q = Quaternion(2f, 3f, 4f, 5f).normalized()
        assertThat(q.length()).isEqualTo(1f, eps)
    }

    @Test
    fun `normalized of zero quaternion returns IDENTITY`() {
        val q = Quaternion(0f, 0f, 0f, 0f).normalized()
        assertThat(q).isEqualTo(Quaternion.IDENTITY)
    }

    @Test
    fun `fromAxisAngle with zero axis returns IDENTITY`() {
        // Defensive: zero axis is meaningless, must not produce NaN
        val q = Quaternion.fromAxisAngle(Vec3.ZERO, (PI / 4).toFloat())
        assertThat(q).isEqualTo(Quaternion.IDENTITY)
    }

    @Test
    fun `fromAxisAngle normalizes a non-unit axis`() {
        // (2, 0, 0) and (1, 0, 0) describe the same rotation axis — outputs equal
        val unit = Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), (PI / 3).toFloat())
        val scaled = Quaternion.fromAxisAngle(Vec3(5.0, 0.0, 0.0), (PI / 3).toFloat())
        assertThat(scaled.x).isEqualTo(unit.x, eps)
        assertThat(scaled.y).isEqualTo(unit.y, eps)
        assertThat(scaled.z).isEqualTo(unit.z, eps)
        assertThat(scaled.w).isEqualTo(unit.w, eps)
    }
}
