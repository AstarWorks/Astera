package com.astarworks.astera.core.domain.geometry.math

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A parametric 3D curve in its LOCAL space (a [Frame] is applied later).
 * `t` is in `[0, 1]` (values outside are clamped). Local axes follow the
 * Minecraft frame convention: +Z forward, +Y up, +X left.
 */
public sealed interface Curve {
    public fun point(t: Double): Vec3

    /** Normalized direction of travel at [t]; zero vector for a degenerate curve. */
    public fun tangent(t: Double): Vec3

    /** True when `point(0) == point(1)` by construction (loops back to its start). */
    public val closed: Boolean get() = false

    /** Straight segment. */
    public data class Line(public val from: Vec3, public val to: Vec3) : Curve {
        override fun point(t: Double): Vec3 = lerp(from, to, t.coerceIn(0.0, 1.0))
        override fun tangent(t: Double): Vec3 = (to - from).normalize()
    }

    /**
     * Arc in the local XZ plane at y = 0. Angle 0 = +Z (forward); positive
     * angles sweep toward +X (left): `point = (r sin a, 0, r cos a)`.
     * `Arc(r, 0, 360)` is a full circle.
     */
    public data class Arc(
        public val radius: Double,
        public val fromDeg: Double,
        public val toDeg: Double,
    ) : Curve {
        override val closed: Boolean get() = toDeg != fromDeg && abs(toDeg - fromDeg) % 360.0 == 0.0

        override fun point(t: Double): Vec3 {
            val a = Math.toRadians(fromDeg + (toDeg - fromDeg) * t.coerceIn(0.0, 1.0))
            return Vec3(radius * sin(a), 0.0, radius * cos(a))
        }

        override fun tangent(t: Double): Vec3 {
            val a = Math.toRadians(fromDeg + (toDeg - fromDeg) * t.coerceIn(0.0, 1.0))
            val dir = if (toDeg >= fromDeg) 1.0 else -1.0
            val sr = if (radius >= 0.0) 1.0 else -1.0
            return Vec3(cos(a) * dir * sr, 0.0, -sin(a) * dir * sr).normalize()
        }
    }

    /** XZ circle (same angle convention as [Arc]) rising along +Y by [height] over [turns] turns. */
    public data class Helix(
        public val radius: Double,
        public val turns: Double,
        public val height: Double,
        public val startDeg: Double = 0.0,
    ) : Curve {
        override fun point(t: Double): Vec3 {
            val u = t.coerceIn(0.0, 1.0)
            val a = Math.toRadians(startDeg + 360.0 * turns * u)
            return Vec3(radius * sin(a), height * u, radius * cos(a))
        }

        override fun tangent(t: Double): Vec3 {
            val u = t.coerceIn(0.0, 1.0)
            val a = Math.toRadians(startDeg + 360.0 * turns * u)
            val w = 2.0 * PI * turns
            return Vec3(radius * cos(a) * w, height, -radius * sin(a) * w).normalize()
        }
    }

    /** Planar XZ spiral whose radius goes linearly from [startRadius] to [endRadius]. */
    public data class Spiral(
        public val startRadius: Double,
        public val endRadius: Double,
        public val turns: Double,
        public val startDeg: Double = 0.0,
    ) : Curve {
        override fun point(t: Double): Vec3 {
            val u = t.coerceIn(0.0, 1.0)
            val r = startRadius + (endRadius - startRadius) * u
            val a = Math.toRadians(startDeg + 360.0 * turns * u)
            return Vec3(r * sin(a), 0.0, r * cos(a))
        }

        override fun tangent(t: Double): Vec3 {
            val u = t.coerceIn(0.0, 1.0)
            val r = startRadius + (endRadius - startRadius) * u
            val dr = endRadius - startRadius
            val a = Math.toRadians(startDeg + 360.0 * turns * u)
            val w = 2.0 * PI * turns
            return Vec3(
                dr * sin(a) + r * cos(a) * w,
                0.0,
                dr * cos(a) - r * sin(a) * w,
            ).normalize()
        }
    }

    /** Bezier of any degree >= 1 (de Casteljau); [controls] has at least 2 points. */
    public data class Bezier(public val controls: List<Vec3>) : Curve {
        init { require(controls.size >= 2) { "Bezier needs >= 2 control points: ${controls.size}" } }

        override fun point(t: Double): Vec3 = deCasteljau(controls, t.coerceIn(0.0, 1.0))

        override fun tangent(t: Double): Vec3 {
            val u = t.coerceIn(0.0, 1.0)
            val n = controls.size - 1
            val deriv = List(n) { (controls[it + 1] - controls[it]) * n.toDouble() }
            val d = deCasteljau(deriv, u).normalize()
            if (d.lengthSquared() > 0.0) return d
            // Coincident control points at an end: look slightly inward.
            val e = 1e-4
            return (point(minOf(1.0, u + e)) - point(maxOf(0.0, u - e))).normalize()
        }
    }

    /**
     * Closed polyline through regular-polygon vertices on the XZ circle
     * (angle convention of [Arc]). [skip] > 1 makes a star (`sides=5, skip=2`
     * = pentagram). Vertex k sits at `rotationDeg + 360 * k * skip / sides`.
     * The path closes after `sides / gcd(sides, skip)` edges.
     */
    public data class Polygon(
        public val radius: Double,
        public val sides: Int,
        public val skip: Int = 1,
        public val rotationDeg: Double = 0.0,
    ) : Curve {
        init {
            require(sides >= 3) { "Polygon.sides must be >= 3: $sides" }
            require(skip >= 1) { "Polygon.skip must be >= 1: $skip" }
        }

        override val closed: Boolean get() = true

        private val vertices: List<Vec3> by lazy {
            val edges = sides / gcd(sides, skip)
            List(edges + 1) { k ->
                val a = Math.toRadians(rotationDeg + 360.0 * (k * skip) / sides)
                Vec3(radius * sin(a), 0.0, radius * cos(a))
            }
        }

        override fun point(t: Double): Vec3 = polylinePoint(vertices, t)
        override fun tangent(t: Double): Vec3 = polylineTangent(vertices, t)
    }

    /** Points spread over a sphere (golden-angle spiral); `point(t)` picks index `round(t*(count-1))`. */
    public data class FibonacciSphere(public val radius: Double, public val count: Int) : Curve {
        init { require(count >= 1) { "FibonacciSphere.count must be >= 1: $count" } }

        private fun indexAt(t: Double): Int =
            (t.coerceIn(0.0, 1.0) * (count - 1)).roundToInt().coerceIn(0, count - 1)

        private fun at(i: Int): Vec3 {
            val y = 1.0 - 2.0 * (i + 0.5) / count
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val phi = i * GOLDEN_ANGLE
            return Vec3(radius * r * cos(phi), radius * y, radius * r * sin(phi))
        }

        override fun point(t: Double): Vec3 = at(indexAt(t))

        override fun tangent(t: Double): Vec3 {
            if (count == 1) return Vec3.ZERO
            val i = indexAt(t)
            return if (i < count - 1) (at(i + 1) - at(i)).normalize() else (at(i) - at(i - 1)).normalize()
        }
    }

    /**
     * Deterministic jagged polyline from [from] to [to] with [segments]
     * segments; interior vertices are displaced perpendicular to the line by
     * up to +-[amplitude] on each perpendicular axis, drawn from [seed].
     */
    public data class Lightning(
        public val from: Vec3,
        public val to: Vec3,
        public val segments: Int,
        public val amplitude: Double,
        public val seed: Long,
    ) : Curve {
        init { require(segments >= 1) { "Lightning.segments must be >= 1: $segments" } }

        private val vertices: List<Vec3> by lazy {
            val dir = (to - from).normalize()
            val helper = if (abs(dir.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
            val p1 = dir.cross(helper).normalize()
            val p2 = dir.cross(p1).normalize()
            val rng = SeededRandom(seed)
            List(segments + 1) { i ->
                val base = lerp(from, to, i.toDouble() / segments)
                if (i == 0 || i == segments) {
                    base
                } else {
                    base + p1 * rng.nextDouble(-amplitude, amplitude) + p2 * rng.nextDouble(-amplitude, amplitude)
                }
            }
        }

        override fun point(t: Double): Vec3 = polylinePoint(vertices, t)
        override fun tangent(t: Double): Vec3 = polylineTangent(vertices, t)
    }

    /** Applies scale, then [rotation], then [offset] to [base]; tilts/rotates a curve's plane. */
    public data class Transformed(
        public val base: Curve,
        public val offset: Vec3 = Vec3.ZERO,
        public val rotation: Quaternion = Quaternion.IDENTITY,
        public val scale: Double = 1.0,
    ) : Curve {
        override val closed: Boolean get() = base.closed
        override fun point(t: Double): Vec3 = rotation.rotate(base.point(t) * scale) + offset
        override fun tangent(t: Double): Vec3 = rotation.rotate(base.tangent(t)).normalize()
    }

    public companion object {
        private val GOLDEN_ANGLE: Double = PI * (3.0 - sqrt(5.0))

        internal fun lerp(a: Vec3, b: Vec3, t: Double): Vec3 = a + (b - a) * t

        internal fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        internal fun deCasteljau(pts: List<Vec3>, t: Double): Vec3 {
            var work = pts
            while (work.size > 1) {
                val prev = work
                work = List(prev.size - 1) { lerp(prev[it], prev[it + 1], t) }
            }
            return work[0]
        }

        /** Point on a polyline with every segment given an equal share of `t`. */
        internal fun polylinePoint(v: List<Vec3>, t: Double): Vec3 {
            val n = v.size - 1
            val s = t.coerceIn(0.0, 1.0) * n
            val i = floor(s).toInt().coerceIn(0, n - 1)
            return lerp(v[i], v[i + 1], s - i)
        }

        internal fun polylineTangent(v: List<Vec3>, t: Double): Vec3 {
            val n = v.size - 1
            val i = floor(t.coerceIn(0.0, 1.0) * n).toInt().coerceIn(0, n - 1)
            return (v[i + 1] - v[i]).normalize()
        }
    }
}

/**
 * Evenly spaced `t` values over `[0, 1]`. Open: includes both endpoints
 * (`count == 1` gives `0.5`). Closed: the end point is not duplicated
 * (`i / count`). [closed] defaults to [Curve.closed].
 */
public fun Curve.distribute(count: Int, closed: Boolean = this.closed): List<Double> {
    if (count <= 0) return emptyList()
    if (closed) return List(count) { it.toDouble() / count }
    if (count == 1) return listOf(0.5)
    return List(count) { it.toDouble() / (count - 1) }
}
