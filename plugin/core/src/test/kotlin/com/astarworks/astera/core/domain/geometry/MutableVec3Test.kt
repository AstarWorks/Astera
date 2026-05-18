package com.astarworks.astera.core.domain.geometry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class MutableVec3Test {

    private val eps = Offset.offset(1e-9)

    @Test
    fun `set chains and returns this`() {
        val v = MutableVec3()
        val returned = v.set(1.0, 2.0, 3.0)

        assertThat(returned).isSameAs(v)
        assertThat(v.x).isEqualTo(1.0)
        assertThat(v.y).isEqualTo(2.0)
        assertThat(v.z).isEqualTo(3.0)
    }

    @Test
    fun `add accumulates correctly`() {
        val v = MutableVec3(1.0, 2.0, 3.0)
        v.add(0.5, 1.5, -1.0)

        assertThat(v.x).isEqualTo(1.5)
        assertThat(v.y).isEqualTo(3.5)
        assertThat(v.z).isEqualTo(2.0)
    }

    @Test
    fun `setFrom copies immutable Vec3`() {
        val immutable = Vec3(7.0, 8.0, 9.0)
        val v = MutableVec3()
        v.setFrom(immutable)

        assertThat(v.x).isEqualTo(7.0)
        assertThat(v.y).isEqualTo(8.0)
        assertThat(v.z).isEqualTo(9.0)
    }

    @Test
    fun `toVec3 produces equal immutable snapshot`() {
        val v = MutableVec3(3.0, 4.0, 5.0)
        val snapshot = v.toVec3()

        assertThat(snapshot).isEqualTo(Vec3(3.0, 4.0, 5.0))
    }

    @Test
    fun `reset returns to zero`() {
        val v = MutableVec3(10.0, 20.0, 30.0)
        v.reset()

        assertThat(v.x).isEqualTo(0.0)
        assertThat(v.y).isEqualTo(0.0)
        assertThat(v.z).isEqualTo(0.0)
    }

    @Test
    fun `scale multiplies all components`() {
        val v = MutableVec3(2.0, 4.0, 6.0)
        v.scale(0.5)

        assertThat(v.x).isEqualTo(1.0)
        assertThat(v.y).isEqualTo(2.0)
        assertThat(v.z).isEqualTo(3.0)
    }

    @Test
    fun `sub mirrors add and chains`() {
        val v = MutableVec3(5.0, 5.0, 5.0)
        val r = v.sub(1.0, 2.0, 3.0)
        assertThat(r).isSameAs(v)
        assertThat(v.x).isEqualTo(4.0)
        assertThat(v.y).isEqualTo(3.0)
        assertThat(v.z).isEqualTo(2.0)
    }

    @Test
    fun `length and lengthSquared compute correctly`() {
        val v = MutableVec3(3.0, 0.0, 4.0)
        assertThat(v.lengthSquared()).isEqualTo(25.0)
        assertThat(v.length()).isEqualTo(5.0, eps)
    }

    @Test
    fun `normalize in place chains and produces unit vector`() {
        val v = MutableVec3(0.0, 0.0, 5.0)
        val r = v.normalize()
        assertThat(r).isSameAs(v)
        assertThat(v.length()).isEqualTo(1.0, eps)
        assertThat(v.z).isEqualTo(1.0, eps)
    }

    @Test
    fun `normalize of zero is a no-op (no NaN)`() {
        val v = MutableVec3(0.0, 0.0, 0.0)
        v.normalize()
        assertThat(v.x).isEqualTo(0.0)
        assertThat(v.y).isEqualTo(0.0)
        assertThat(v.z).isEqualTo(0.0)
    }

    @Test
    fun `cross with self alias produces correct result`() {
        val a = MutableVec3(1.0, 2.0, 3.0)
        val b = Vec3(4.0, 5.0, 6.0)
        a.cross(b)
        // 1,2,3 × 4,5,6 = (2*6-3*5, 3*4-1*6, 1*5-2*4) = (-3, 6, -3)
        assertThat(a.x).isEqualTo(-3.0)
        assertThat(a.y).isEqualTo(6.0)
        assertThat(a.z).isEqualTo(-3.0)
    }

    @Test
    fun `dot with immutable Vec3`() {
        val a = MutableVec3(2.0, 0.0, 0.0)
        assertThat(a.dot(Vec3(3.0, 0.0, 0.0))).isEqualTo(6.0)
    }
}
