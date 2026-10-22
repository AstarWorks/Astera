package com.astarworks.astera.core.domain.geometry.math

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class CurveTest {
    private val eps = Offset.offset(1e-9)

    private fun assertVec(actual: Vec3, x: Double, y: Double, z: Double, o: Offset<Double> = eps) {
        assertThat(actual.x).isEqualTo(x, o)
        assertThat(actual.y).isEqualTo(y, o)
        assertThat(actual.z).isEqualTo(z, o)
    }

    /** Polyline length by dense sampling. */
    private fun length(c: Curve, n: Int = 2000): Double {
        var sum = 0.0
        var prev = c.point(0.0)
        for (i in 1..n) {
            val p = c.point(i.toDouble() / n)
            sum += p.distance(prev)
            prev = p
        }
        return sum
    }

    @Test
    fun `line endpoints midpoint tangent`() {
        val c = Curve.Line(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 10.0))
        assertVec(c.point(0.0), 0.0, 0.0, 0.0)
        assertVec(c.point(1.0), 0.0, 0.0, 10.0)
        assertVec(c.point(0.5), 0.0, 0.0, 5.0)
        assertVec(c.tangent(0.3), 0.0, 0.0, 1.0)
        assertThat(length(c)).isEqualTo(10.0, Offset.offset(1e-6))
    }

    @Test
    fun `t is clamped`() {
        val c = Curve.Line(Vec3.ZERO, Vec3(1.0, 0.0, 0.0))
        assertVec(c.point(-3.0), 0.0, 0.0, 0.0)
        assertVec(c.point(7.0), 1.0, 0.0, 0.0)
    }

    @Test
    fun `arc angle 0 is forward and positive sweeps to +X`() {
        val c = Curve.Arc(2.0, 0.0, 90.0)
        assertVec(c.point(0.0), 0.0, 0.0, 2.0)
        assertVec(c.point(1.0), 2.0, 0.0, 0.0)
        // at the start we move toward +X
        assertVec(c.tangent(0.0), 1.0, 0.0, 0.0)
        assertThat(length(c)).isEqualTo(Math.PI, Offset.offset(1e-4))
    }

    @Test
    fun `arc negative sweep goes to -X and tangent follows`() {
        val c = Curve.Arc(1.0, 0.0, -90.0)
        assertVec(c.point(1.0), -1.0, 0.0, 0.0)
        assertVec(c.tangent(0.0), -1.0, 0.0, 0.0)
    }

    @Test
    fun `arc tangent matches numeric derivative`() {
        val c = Curve.Arc(3.0, -70.0, 70.0)
        for (t in listOf(0.1, 0.5, 0.9)) {
            val n = (c.point(t + 1e-6) - c.point(t - 1e-6)).normalize()
            assertVec(c.tangent(t), n.x, n.y, n.z, Offset.offset(1e-5))
        }
    }

    @Test
    fun `full circle is closed with circumference`() {
        val c = Curve.Arc(2.0, 0.0, 360.0)
        assertThat(c.closed).isTrue()
        assertThat(Curve.Arc(2.0, 0.0, 90.0).closed).isFalse()
        assertThat(Curve.Arc(2.0, 0.0, 0.0).closed).isFalse()
        assertVec(c.point(0.0), c.point(1.0).x, c.point(1.0).y, c.point(1.0).z, Offset.offset(1e-9))
        assertThat(length(c)).isEqualTo(4.0 * Math.PI, Offset.offset(1e-3))
        for (i in 0..10) assertThat(c.point(i / 10.0).length()).isEqualTo(2.0, eps)
    }

    @Test
    fun `helix rises and keeps radius`() {
        val c = Curve.Helix(radius = 1.5, turns = 2.0, height = 4.0)
        assertVec(c.point(0.0), 0.0, 0.0, 1.5)
        assertVec(c.point(1.0), 0.0, 4.0, 1.5, Offset.offset(1e-9))
        assertVec(c.point(0.5), 0.0, 2.0, 1.5, Offset.offset(1e-9))
        for (i in 0..20) {
            val p = c.point(i / 20.0)
            assertThat(Math.hypot(p.x, p.z)).isEqualTo(1.5, eps)
        }
        val expected = Math.sqrt(Math.pow(2 * Math.PI * 1.5 * 2.0, 2.0) + 16.0)
        assertThat(length(c)).isEqualTo(expected, Offset.offset(1e-2))
    }

    @Test
    fun `helix start angle offsets`() {
        val c = Curve.Helix(1.0, 1.0, 1.0, startDeg = 90.0)
        assertVec(c.point(0.0), 1.0, 0.0, 0.0)
    }

    @Test
    fun `helix tangent has upward component and unit length`() {
        val c = Curve.Helix(1.0, 3.0, 2.0)
        for (t in listOf(0.0, 0.3, 1.0)) {
            val tan = c.tangent(t)
            assertThat(tan.length()).isEqualTo(1.0, eps)
            assertThat(tan.y).isGreaterThan(0.0)
        }
    }

    @Test
    fun `spiral radius interpolates`() {
        val c = Curve.Spiral(1.0, 3.0, 2.0)
        assertThat(c.point(0.0).length()).isEqualTo(1.0, eps)
        assertThat(c.point(1.0).length()).isEqualTo(3.0, eps)
        assertThat(c.point(0.5).length()).isEqualTo(2.0, eps)
        assertThat(c.point(0.37).y).isEqualTo(0.0, eps)
    }

    @Test
    fun `spiral tangent matches numeric derivative`() {
        val c = Curve.Spiral(0.5, 4.0, 1.5, 20.0)
        for (t in listOf(0.0, 0.25, 0.6, 1.0)) {
            val lo = maxOf(0.0, t - 1e-6)
            val hi = minOf(1.0, t + 1e-6)
            val n = (c.point(hi) - c.point(lo)).normalize()
            assertVec(c.tangent(t), n.x, n.y, n.z, Offset.offset(1e-4))
        }
    }

    @Test
    fun `bezier degree 1 equals line`() {
        val c = Curve.Bezier(listOf(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 4.0, 6.0)))
        assertVec(c.point(0.5), 1.0, 2.0, 3.0)
        val n = Vec3(2.0, 4.0, 6.0).normalize()
        assertVec(c.tangent(0.4), n.x, n.y, n.z)
    }

    @Test
    fun `bezier quadratic midpoint and endpoints`() {
        val c = Curve.Bezier(listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 2.0, 0.0), Vec3(2.0, 0.0, 0.0)))
        assertVec(c.point(0.0), 0.0, 0.0, 0.0)
        assertVec(c.point(1.0), 2.0, 0.0, 0.0)
        assertVec(c.point(0.5), 1.0, 1.0, 0.0)
        // start tangent points toward first control leg
        val n = Vec3(1.0, 2.0, 0.0).normalize()
        assertVec(c.tangent(0.0), n.x, n.y, n.z)
        assertVec(c.tangent(0.5), 1.0, 0.0, 0.0)
    }

    @Test
    fun `bezier cubic tangent matches numeric and degenerate end falls back`() {
        val c = Curve.Bezier(listOf(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(2.0, 0.0, 0.0)))
        assertThat(c.tangent(0.0).length()).isEqualTo(1.0, Offset.offset(1e-9))
        val n = (c.point(0.5 + 1e-6) - c.point(0.5 - 1e-6)).normalize()
        assertVec(c.tangent(0.5), n.x, n.y, n.z, Offset.offset(1e-5))
    }

    @Test
    fun `bezier requires two controls`() {
        assertThatThrownBy { Curve.Bezier(listOf(Vec3.ZERO)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `polygon square vertices and closure`() {
        val c = Curve.Polygon(radius = 1.0, sides = 4)
        assertThat(c.closed).isTrue()
        assertVec(c.point(0.0), 0.0, 0.0, 1.0)
        assertVec(c.point(0.25), 1.0, 0.0, 0.0, Offset.offset(1e-9))
        assertVec(c.point(0.5), 0.0, 0.0, -1.0, Offset.offset(1e-9))
        assertVec(c.point(1.0), 0.0, 0.0, 1.0, Offset.offset(1e-9))
        // edge midpoint
        assertVec(c.point(0.125), 0.5, 0.0, 0.5, Offset.offset(1e-9))
        // perimeter = 4 * sqrt(2)
        assertThat(length(c)).isEqualTo(4.0 * Math.sqrt(2.0), Offset.offset(1e-6))
    }

    @Test
    fun `pentagram skip 2 visits every vertex once and is closed`() {
        val c = Curve.Polygon(radius = 2.0, sides = 5, skip = 2)
        assertVec(c.point(0.0), 0.0, 0.0, 2.0)
        assertVec(c.point(1.0), 0.0, 0.0, 2.0, Offset.offset(1e-9))
        val verts = (0 until 5).map { c.point(it / 5.0) }
        // all on the circle, all distinct
        verts.forEach { assertThat(it.length()).isEqualTo(2.0, Offset.offset(1e-9)) }
        for (i in 0 until 5) for (j in i + 1 until 5) assertThat(verts[i].distance(verts[j])).isGreaterThan(1.0)
        // chord length for skip 2 on r=2 pentagon: 2*r*sin(2*pi/5)
        val chord = 2.0 * 2.0 * Math.sin(2.0 * Math.PI / 5.0)
        assertThat(verts[0].distance(verts[1])).isEqualTo(chord, Offset.offset(1e-9))
    }

    @Test
    fun `hexagram skip 2 closes after 3 edges`() {
        val c = Curve.Polygon(radius = 1.0, sides = 6, skip = 2)
        assertVec(c.point(1.0), 0.0, 0.0, 1.0, Offset.offset(1e-9))
        // 3 edges: t = 1/3 lands on vertex 2
        val a = Math.toRadians(120.0)
        assertVec(c.point(1.0 / 3.0), Math.sin(a), 0.0, Math.cos(a), Offset.offset(1e-9))
    }

    @Test
    fun `polygon rotation and tangent`() {
        val c = Curve.Polygon(radius = 1.0, sides = 4, rotationDeg = 90.0)
        assertVec(c.point(0.0), 1.0, 0.0, 0.0, Offset.offset(1e-9))
        assertThat(c.tangent(0.1).length()).isEqualTo(1.0, eps)
        assertThatThrownBy { Curve.Polygon(1.0, 2) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Curve.Polygon(1.0, 5, skip = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `fibonacci sphere points lie on sphere and are distinct`() {
        val c = Curve.FibonacciSphere(radius = 3.0, count = 50)
        val pts = (0 until 50).map { c.point(it / 49.0) }
        pts.forEach { assertThat(it.length()).isEqualTo(3.0, Offset.offset(1e-9)) }
        assertThat(pts.toSet()).hasSize(50)
        assertThat(c.point(0.0).y).isGreaterThan(2.5)
        assertThat(c.point(1.0).y).isLessThan(-2.5)
        assertThat(c.tangent(0.5).length()).isEqualTo(1.0, eps)
        assertThat(c.tangent(1.0).length()).isEqualTo(1.0, eps)
    }

    @Test
    fun `fibonacci single point`() {
        val c = Curve.FibonacciSphere(1.0, 1)
        assertThat(c.point(0.5).length()).isEqualTo(1.0, eps)
        assertThat(c.tangent(0.5)).isEqualTo(Vec3.ZERO)
    }

    @Test
    fun `lightning is deterministic with exact endpoints`() {
        val from = Vec3(0.0, 0.0, 0.0)
        val to = Vec3(0.0, 0.0, 10.0)
        val a = Curve.Lightning(from, to, 8, 0.5, seed = 99)
        val b = Curve.Lightning(from, to, 8, 0.5, seed = 99)
        val other = Curve.Lightning(from, to, 8, 0.5, seed = 100)
        assertVec(a.point(0.0), 0.0, 0.0, 0.0)
        assertVec(a.point(1.0), 0.0, 0.0, 10.0)
        for (i in 0..8) assertThat(a.point(i / 8.0)).isEqualTo(b.point(i / 8.0))
        assertThat((1..7).any { a.point(it / 8.0) != other.point(it / 8.0) }).isTrue()
    }

    @Test
    fun `lightning interior is perpendicular-displaced within amplitude`() {
        val a = Curve.Lightning(Vec3.ZERO, Vec3(0.0, 0.0, 10.0), 10, 0.5, seed = 5)
        var jagged = false
        for (i in 1..9) {
            val p = a.point(i / 10.0)
            assertThat(p.z).isEqualTo(i.toDouble(), eps)
            assertThat(Math.abs(p.x)).isLessThanOrEqualTo(0.5 + 1e-9)
            assertThat(Math.abs(p.y)).isLessThanOrEqualTo(0.5 + 1e-9)
            if (p.x != 0.0 || p.y != 0.0) jagged = true
        }
        assertThat(jagged).isTrue()
        assertThat(a.tangent(0.55).length()).isEqualTo(1.0, eps)
    }

    @Test
    fun `lightning vertical bolt works`() {
        val c = Curve.Lightning(Vec3(0.0, 10.0, 0.0), Vec3.ZERO, 6, 0.4, seed = 1)
        assertVec(c.point(1.0), 0.0, 0.0, 0.0)
        for (i in 1..5) assertThat(c.point(i / 6.0).y).isEqualTo(10.0 - i * 10.0 / 6.0, eps)
    }

    @Test
    fun `transformed applies scale rotation offset`() {
        val base = Curve.Line(Vec3.ZERO, Vec3(0.0, 0.0, 1.0))
        val rot = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI / 2)
        val c = Curve.Transformed(base, offset = Vec3(0.0, 5.0, 0.0), rotation = rot, scale = 2.0)
        assertVec(c.point(0.0), 0.0, 5.0, 0.0)
        assertVec(c.point(1.0), 2.0, 5.0, 0.0)
        assertVec(c.tangent(0.5), 1.0, 0.0, 0.0)
    }

    @Test
    fun `transformed tilt turns circle plane`() {
        val circle = Curve.Arc(1.0, 0.0, 360.0)
        // roll the XZ plane 90 degrees about the forward (+Z) axis -> circle in the YZ... plane spanned by X->Y
        val tilted = Curve.Transformed(circle, rotation = Quaternion.fromEulerDegrees(0.0, 90.0, 0.0))
        assertThat(tilted.closed).isTrue()
        // pitch 90 maps Z -> -Y ; point(0) = (0,0,1) -> (0,-1,0)
        assertVec(tilted.point(0.0), 0.0, -1.0, 0.0)
        for (i in 0..8) assertThat(tilted.point(i / 8.0).length()).isEqualTo(1.0, eps)
    }

    @Test
    fun `distribute open includes endpoints`() {
        val c = Curve.Line(Vec3.ZERO, Vec3(1.0, 0.0, 0.0))
        assertThat(c.distribute(5, closed = false)).containsExactly(0.0, 0.25, 0.5, 0.75, 1.0)
        assertThat(c.distribute(1, closed = false)).containsExactly(0.5)
        assertThat(c.distribute(2, closed = false)).containsExactly(0.0, 1.0)
        assertThat(c.distribute(0, closed = false)).isEmpty()
    }

    @Test
    fun `distribute closed does not duplicate endpoint`() {
        val c = Curve.Arc(1.0, 0.0, 360.0)
        val ts = c.distribute(4, closed = true)
        assertThat(ts).containsExactly(0.0, 0.25, 0.5, 0.75)
        assertThat(c.distribute(4)).isEqualTo(ts)
        val pts = ts.map { c.point(it) }.toSet()
        assertThat(pts.map { Pair(Math.round(it.x * 1e6), Math.round(it.z * 1e6)) }.toSet()).hasSize(4)
    }

    @Test
    fun `distribute defaults to open for open curve`() {
        val c = Curve.Arc(1.0, 0.0, 90.0)
        assertThat(c.distribute(3)).containsExactly(0.0, 0.5, 1.0)
    }
}
