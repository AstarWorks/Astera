package com.astarworks.astera.core.domain.geometry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class Vec3Test {

    private val eps = Offset.offset(1e-9)

    @Test
    fun `length and lengthSquared compute correctly`() {
        val v = Vec3(3.0, 0.0, 4.0)
        assertThat(v.lengthSquared()).isEqualTo(25.0)
        assertThat(v.length()).isEqualTo(5.0, eps)
    }

    @Test
    fun `normalize produces unit vector`() {
        val v = Vec3(0.0, 0.0, 5.0).normalize()
        assertThat(v.length()).isEqualTo(1.0, eps)
        assertThat(v.x).isEqualTo(0.0, eps)
        assertThat(v.y).isEqualTo(0.0, eps)
        assertThat(v.z).isEqualTo(1.0, eps)
    }

    @Test
    fun `normalize of zero vector returns ZERO and does not throw`() {
        assertThat(Vec3.ZERO.normalize()).isEqualTo(Vec3.ZERO)
    }

    @Test
    fun `dot of orthogonal axes is zero`() {
        assertThat(Vec3(1.0, 0.0, 0.0).dot(Vec3(0.0, 1.0, 0.0))).isEqualTo(0.0)
    }

    @Test
    fun `dot of parallel vectors is positive product`() {
        assertThat(Vec3(2.0, 0.0, 0.0).dot(Vec3(3.0, 0.0, 0.0))).isEqualTo(6.0)
    }

    @Test
    fun `cross product of x and y axes returns z axis`() {
        val xAxis = Vec3(1.0, 0.0, 0.0)
        val yAxis = Vec3(0.0, 1.0, 0.0)
        assertThat(xAxis.cross(yAxis)).isEqualTo(Vec3(0.0, 0.0, 1.0))
    }

    @Test
    fun `cross product is orthogonal to both operands`() {
        val a = Vec3(1.0, 2.0, 3.0)
        val b = Vec3(4.0, 5.0, 6.0)
        val c = a.cross(b)
        assertThat(c.dot(a)).isEqualTo(0.0, eps)
        assertThat(c.dot(b)).isEqualTo(0.0, eps)
    }

    @Test
    fun `distance computes between two points`() {
        val a = Vec3(0.0, 0.0, 0.0)
        val b = Vec3(3.0, 0.0, 4.0)
        assertThat(a.distance(b)).isEqualTo(5.0, eps)
        assertThat(a.distanceSquared(b)).isEqualTo(25.0)
    }
}
