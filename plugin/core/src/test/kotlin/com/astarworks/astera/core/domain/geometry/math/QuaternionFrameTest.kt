package com.astarworks.astera.core.domain.geometry.math

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class QuaternionFrameTest {
    private val eps = Offset.offset(1e-9)

    private fun assertVec(actual: Vec3, x: Double, y: Double, z: Double) {
        assertThat(actual.x).isEqualTo(x, eps)
        assertThat(actual.y).isEqualTo(y, eps)
        assertThat(actual.z).isEqualTo(z, eps)
    }

    @Test
    fun `identity rotate is no-op`() {
        assertVec(Quaternion.IDENTITY.rotate(Vec3(1.0, 2.0, 3.0)), 1.0, 2.0, 3.0)
    }

    @Test
    fun `axisAngle 90 about Y maps Z to X`() {
        val q = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI / 2)
        assertVec(q.rotate(Vec3(0.0, 0.0, 1.0)), 1.0, 0.0, 0.0)
    }

    @Test
    fun `axisAngle normalizes axis and zero axis is identity`() {
        val q = Quaternion.axisAngle(Vec3(0.0, 5.0, 0.0), Math.PI)
        assertVec(q.rotate(Vec3(1.0, 0.0, 0.0)), -1.0, 0.0, 0.0)
        assertThat(Quaternion.axisAngle(Vec3.ZERO, 1.0)).isEqualTo(Quaternion.IDENTITY)
    }

    @Test
    fun `times applies right operand first`() {
        val rx = Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.PI / 2)
        val ry = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI / 2)
        val v = Vec3(0.0, 0.0, 1.0)
        assertVec((ry * rx).rotate(v), ry.rotate(rx.rotate(v)).x, ry.rotate(rx.rotate(v)).y, ry.rotate(rx.rotate(v)).z)
    }

    @Test
    fun `conjugate inverts rotation`() {
        val q = Quaternion.fromEulerDegrees(33.0, -20.0, 70.0)
        val v = Vec3(0.3, -1.2, 2.0)
        assertVec(q.conjugate().rotate(q.rotate(v)), v.x, v.y, v.z)
        val id = q * q.conjugate()
        assertThat(id.w).isEqualTo(1.0, eps)
    }

    @Test
    fun `normalized gives unit length and zero becomes identity`() {
        assertThat(Quaternion(0.0, 0.0, 0.0, 2.0).normalized()).isEqualTo(Quaternion.IDENTITY)
        assertThat(Quaternion(0.0, 0.0, 0.0, 0.0).normalized()).isEqualTo(Quaternion.IDENTITY)
        assertThat(Quaternion(1.0, 2.0, 3.0, 4.0).normalized().lengthSquared()).isEqualTo(1.0, eps)
    }

    @Test
    fun `slerp endpoints and midpoint`() {
        val a = Quaternion.IDENTITY
        val b = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI / 2)
        assertThat(Quaternion.slerp(a, b, 0.0).angleTo(a)).isEqualTo(0.0, eps)
        assertThat(Quaternion.slerp(a, b, 1.0).angleTo(b)).isEqualTo(0.0, eps)
        val mid = Quaternion.slerp(a, b, 0.5)
        assertThat(mid.angleTo(a)).isEqualTo(Math.PI / 4, eps)
        assertThat(mid.angleTo(b)).isEqualTo(Math.PI / 4, eps)
    }

    @Test
    fun `slerp takes shortest path across negated quaternion`() {
        val a = Quaternion.IDENTITY
        val b = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI / 2)
        val bNeg = Quaternion(-b.x, -b.y, -b.z, -b.w)
        val mid = Quaternion.slerp(a, bNeg, 0.5)
        assertThat(mid.angleTo(a)).isEqualTo(Math.PI / 4, eps)
    }

    @Test
    fun `slerp of nearly identical quaternions stays unit`() {
        val a = Quaternion.IDENTITY
        val b = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), 1e-6)
        assertThat(Quaternion.slerp(a, b, 0.5).lengthSquared()).isEqualTo(1.0, eps)
    }

    @Test
    fun `angleTo is symmetric and treats q and -q as same`() {
        val a = Quaternion.fromEulerDegrees(10.0, 20.0, 30.0)
        val b = Quaternion.fromEulerDegrees(-40.0, 5.0, 0.0)
        assertThat(a.angleTo(b)).isEqualTo(b.angleTo(a), eps)
        assertThat(a.angleTo(Quaternion(-a.x, -a.y, -a.z, -a.w))).isEqualTo(0.0, eps)
    }

    @Test
    fun `euler yaw convention is Minecraft`() {
        val fwd = Vec3(0.0, 0.0, 1.0)
        assertVec(Quaternion.fromEulerDegrees(0.0, 0.0, 0.0).rotate(fwd), 0.0, 0.0, 1.0)
        // yaw 90 faces -X (west)
        assertVec(Quaternion.fromEulerDegrees(90.0, 0.0, 0.0).rotate(fwd), -1.0, 0.0, 0.0)
        // yaw 180 faces -Z (north), yaw -90 / 270 faces +X (east)
        assertVec(Quaternion.fromEulerDegrees(180.0, 0.0, 0.0).rotate(fwd), 0.0, 0.0, -1.0)
        assertVec(Quaternion.fromEulerDegrees(-90.0, 0.0, 0.0).rotate(fwd), 1.0, 0.0, 0.0)
    }

    @Test
    fun `euler pitch positive looks down`() {
        val f = Quaternion.fromEulerDegrees(0.0, 90.0, 0.0).rotate(Vec3(0.0, 0.0, 1.0))
        assertVec(f, 0.0, -1.0, 0.0)
    }

    @Test
    fun `euler roll leaves forward unchanged and turns up`() {
        val q = Quaternion.fromEulerDegrees(0.0, 0.0, 90.0)
        assertVec(q.rotate(Vec3(0.0, 0.0, 1.0)), 0.0, 0.0, 1.0)
        assertVec(q.rotate(Vec3(0.0, 1.0, 0.0)), -1.0, 0.0, 0.0)
    }

    @Test
    fun `lookRotation maps Z to forward`() {
        val dirs = listOf(
            Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0),
            Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0), Vec3(1.0, 2.0, -3.0), Vec3(-2.0, -1.0, 0.5),
        )
        for (d in dirs) {
            val n = d.normalize()
            val f = Quaternion.lookRotation(d).rotate(Vec3(0.0, 0.0, 1.0))
            assertVec(f, n.x, n.y, n.z)
        }
    }

    @Test
    fun `lookRotation up vector is honoured`() {
        val q = Quaternion.lookRotation(Vec3(0.0, 0.0, 1.0))
        assertVec(q.rotate(Vec3(0.0, 1.0, 0.0)), 0.0, 1.0, 0.0)
        assertVec(q.rotate(Vec3(1.0, 0.0, 0.0)), 1.0, 0.0, 0.0)
        val tilted = Quaternion.lookRotation(Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0))
        // up = +X -> local +Y maps to +X
        assertVec(tilted.rotate(Vec3(0.0, 1.0, 0.0)), 1.0, 0.0, 0.0)
    }

    @Test
    fun `lookRotation zero forward is identity`() {
        assertThat(Quaternion.lookRotation(Vec3.ZERO)).isEqualTo(Quaternion.IDENTITY)
    }

    @Test
    fun `euler and lookRotation agree on yaw pitch`() {
        for (yaw in listOf(-170.0, -90.0, -30.0, 0.0, 45.0, 90.0, 135.0, 180.0)) {
            for (pitch in listOf(-80.0, -30.0, 0.0, 30.0, 80.0)) {
                val e = Quaternion.fromEulerDegrees(yaw, pitch, 0.0)
                val fwd = e.rotate(Vec3(0.0, 0.0, 1.0))
                val l = Quaternion.lookRotation(fwd)
                assertThat(e.angleTo(l)).describedAs("yaw=$yaw pitch=$pitch").isLessThan(1e-6)
            }
        }
    }

    @Test
    fun `Minecraft direction vector matches euler forward`() {
        val yaw = 30.0
        val pitch = 20.0
        val yr = Math.toRadians(yaw)
        val pr = Math.toRadians(pitch)
        // vanilla getLookAngle: (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
        val f = Quaternion.fromEulerDegrees(yaw, pitch, 0.0).rotate(Vec3(0.0, 0.0, 1.0))
        assertVec(f, -Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr))
    }

    @Test
    fun `frame axes and toWorld`() {
        val f = Frame(Vec3(10.0, 64.0, -5.0))
        assertVec(f.forward(), 0.0, 0.0, 1.0)
        assertVec(f.up(), 0.0, 1.0, 0.0)
        assertVec(f.left(), 1.0, 0.0, 0.0)
        assertVec(f.right(), -1.0, 0.0, 0.0)
        assertVec(f.toWorld(Vec3(1.0, 2.0, 3.0)), 11.0, 66.0, -2.0)
    }

    @Test
    fun `frame fromYawPitch faces west at yaw 90`() {
        val f = Frame.fromYawPitch(Vec3.ZERO, 90.0, 0.0)
        assertVec(f.forward(), -1.0, 0.0, 0.0)
        // facing west, caster's left is south (+Z)
        assertVec(f.left(), 0.0, 0.0, 1.0)
        assertVec(f.toWorld(Vec3(0.0, 0.0, 2.0)), -2.0, 0.0, 0.0)
    }

    @Test
    fun `frame fromDirection`() {
        val f = Frame.fromDirection(Vec3(1.0, 1.0, 1.0), Vec3(0.0, 0.0, -4.0))
        assertVec(f.forward(), 0.0, 0.0, -1.0)
        assertVec(f.toWorld(Vec3(0.0, 0.0, 3.0)), 1.0, 1.0, -2.0)
    }

    @Test
    fun `frame compose chains`() {
        val parent = Frame.fromYawPitch(Vec3(5.0, 0.0, 0.0), 90.0, 0.0)
        val child = Frame(Vec3(0.0, 0.0, 1.0), Quaternion.fromEulerDegrees(90.0, 0.0, 0.0))
        val c = parent.compose(child)
        assertVec(c.origin, 4.0, 0.0, 0.0)
        // two 90-degree yaws = facing -Z
        assertVec(c.forward(), 0.0, 0.0, -1.0)
    }

    @Test
    fun `easing fromKey`() {
        assertThat(Easing.fromKey("linear")).isEqualTo(Easing.LINEAR)
        assertThat(Easing.fromKey("out_cubic")).isEqualTo(Easing.EASE_OUT_CUBIC)
        assertThat(Easing.fromKey("in_out_back")).isEqualTo(Easing.EASE_IN_OUT_BACK)
        assertThat(Easing.fromKey("OUT-EXPO")).isEqualTo(Easing.EASE_OUT_EXPO)
        assertThat(Easing.fromKey("ease_in_quad")).isEqualTo(Easing.EASE_IN_QUAD)
        assertThat(Easing.fromKey("nope")).isNull()
        assertThat(Easing.fromKey("")).isNull()
    }

    @Test
    fun `every easing round trips through its key`() {
        for (e in Easing.entries) {
            val key = e.name.removePrefix("EASE_").lowercase()
            assertThat(Easing.fromKey(key)).isEqualTo(e)
        }
    }

    @Test
    fun `seeded random is deterministic and in range`() {
        val a = SeededRandom(42)
        val b = SeededRandom(42)
        repeat(100) { assertThat(a.nextLong()).isEqualTo(b.nextLong()) }
        val r = SeededRandom(7)
        repeat(1000) {
            val d = r.nextDouble(-2.0, 3.0)
            assertThat(d).isBetween(-2.0, 3.0)
            assertThat(r.nextUnitVector().length()).isEqualTo(1.0, Offset.offset(1e-9))
        }
        assertThat(SeededRandom(1).nextLong()).isNotEqualTo(SeededRandom(2).nextLong())
    }

    @Test
    fun `splitmix64 matches reference first output for seed 0`() {
        // Reference splitmix64(0) first output = 0xE220A8397B1DCDAF
        assertThat(SeededRandom(0).nextLong()).isEqualTo(-0x1ddf57c684e23251L)
    }
}
