package com.astarworks.astera.itemart

import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Smooth, anti-aliased frame painters for retextured vanilla particle types (ADR-0252). Unlike
 * [ParticlePainters] (4-tone pixel ramps, binary alpha, for TextDisplay sprites) these evaluate a continuous
 * colour + alpha field per sub-sample (4x4 supersampling), so a particle magnified beside the camera shows soft
 * falloff instead of square pixels. Every frame is fully transparent at its border (no quad edge ever shows).
 * Deterministic: the only randomness is an integer hash.
 */
internal object ParticleSkinPainters {

    /** Straight (non-premultiplied) colour 0..1 and coverage 0..1 at one point. */
    class Px(val r: Double, val g: Double, val b: Double, val a: Double)

    /** Point (x, y in -1..1, y down) to colour + alpha. */
    private fun interface Field {
        fun at(x: Double, y: Double): Px
    }

    /** Builds the field of frame [i] of [n]; [t] = i / (n - 1). */
    private fun interface Painter {
        fun frame(t: Double, i: Int, n: Int): Field
    }

    private val CLEAR = Px(1.0, 1.0, 1.0, 0.0)
    private val EMPTY = Field { _, _ -> CLEAR }

    private val PAINTERS: Map<String, Painter> = mapOf(
        "ember" to Painter(::ember),
        "spark" to Painter(::spark),
        "smoke" to Painter(::smoke),
    )

    val NAMES: Set<String> get() = PAINTERS.keys

    /**
     * [count] frames of [name] at [size] x [size] px. A [grey] skin keeps the luminance only (the particle's
     * server colour multiplies it); a baked skin keeps the painted colour.
     */
    fun frames(name: String, count: Int, size: Int, grey: Boolean): List<BufferedImage> {
        val painter = requireNotNull(PAINTERS[name]) { "unknown particle skin painter '$name' (known: ${PAINTERS.keys})" }
        return (0 until count).map { i ->
            val t = if (count <= 1) 0.0 else i.toDouble() / (count - 1)
            raster(painter.frame(t, i, count), size, grey)
        }
    }

    private const val SS = 4

    private fun raster(field: Field, size: Int, grey: Boolean): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (py in 0 until size) for (px in 0 until size) {
            var sa = 0.0; var sr = 0.0; var sg = 0.0; var sb = 0.0
            var ur = 0.0; var ug = 0.0; var ub = 0.0
            for (oy in 0 until SS) for (ox in 0 until SS) {
                val x = ((px + (ox + 0.5) / SS) / size) * 2.0 - 1.0
                val y = ((py + (oy + 0.5) / SS) / size) * 2.0 - 1.0
                val p = field.at(x, y)
                sa += p.a; sr += p.r * p.a; sg += p.g * p.a; sb += p.b * p.a
                ur += p.r; ug += p.g; ub += p.b
            }
            val n = (SS * SS).toDouble()
            val a = sa / n
            // Colour of transparent texels = the unweighted mean, so linear filtering never pulls in a dark fringe.
            var r = if (sa > 1e-9) sr / sa else ur / n
            var g = if (sa > 1e-9) sg / sa else ug / n
            var b = if (sa > 1e-9) sb / sa else ub / n
            if (grey) {
                val l = 0.299 * r + 0.587 * g + 0.114 * b
                r = l; g = l; b = l
            }
            img.setRGB(px, py, (to8(a) shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b))
        }
        return img
    }

    private fun to8(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()

    // ---- ember (entity_effect, grey, server-tinted) -------------------------------------------------

    /**
     * A lumpy glowing ember: a hot core that is the full tint colour with a darker rim, a faint halo, an outline
     * that reshapes every frame (flicker) and a few specks orbiting it. It shrinks while it cools and fades out,
     * since `entity_effect` has no fade of its own.
     */
    private fun ember(t: Double, i: Int, n: Int): Field {
        val shrink = smooth(0.0, 1.0, t)
        val rBase = mix(0.50, 0.19, shrink) * (1.0 + 0.30 * (hash(i, 3, 11) - 0.5))
        val cx = (hash(i, 1, 11) - 0.5) * 0.10
        val cy = (hash(i, 2, 11) - 0.5) * 0.10 - 0.05 * t
        val pa = hash(i, 4, 11) * 2 * PI
        val pb = hash(i, 5, 11) * 2 * PI
        val fade = 1.0 - 0.97 * smooth(0.5, 1.0, t)
        val specks = (0 until 3).map { k ->
            val ang = hash(i, 20 + k, 11) * 2 * PI
            val rad = rBase * (1.25 + 0.9 * hash(i, 30 + k, 11))
            Triple(cx + rad * cos(ang), cy + rad * sin(ang), 0.030 + 0.012 * hash(i, 40 + k, 11))
        }
        return Field { x, y ->
            val dx = x - cx; val dy = y - cy
            val r = hypot(dx, dy)
            val th = atan2(dy, dx)
            val rr = rBase * (1.0 + 0.07 * sin(2 * th + pa) + 0.05 * sin(5 * th + pb))
            val q = r / rr
            val core = exp(-q * q * 2.2) * (0.88 + 0.12 * sin(i * 2.1 + 1.0))
            val halo = 0.26 * exp(-(r / (rBase * 2.2)).pow(2) * 2.0)
            var a = (core * 1.25 + halo).coerceIn(0.0, 1.0)
            var speck = 0.0
            for ((sx, sy, w) in specks) speck += exp(-((x - sx).pow(2) + (y - sy).pow(2)) / (w * w))
            a = (a + 0.7 * speck * (1.0 - t)).coerceIn(0.0, 1.0)
            val g = 0.60 + 0.40 * exp(-q * q * 5.0)
            Px(g, g, g, a * fade * edge(x, y))
        }
    }

    // ---- spark (firework, baked) ---------------------------------------------------------------------

    /** Frames that show the spark; the rest of the sprite list is clear (vanilla `firework` lives 48-60 ticks). */
    const val SPARK_ACTIVE: Int = 8

    /**
     * A hot gold glint: white core, gold halo and two thin tapered streaks that turn a little every frame. It cools
     * white -> gold -> orange -> red while it shrinks, and the frames after [SPARK_ACTIVE] are empty, so the
     * particle is gone ~12-15 ticks after it spawns although the client keeps it alive.
     */
    private fun spark(t: Double, i: Int, n: Int): Field {
        if (i >= SPARK_ACTIVE) return EMPTY
        val u = i / (SPARK_ACTIVE - 1.0)
        val warm = ramp(u, listOf(
            0.0 to doubleArrayOf(1.0, 0.96, 0.78),
            0.3 to doubleArrayOf(1.0, 0.80, 0.30),
            0.65 to doubleArrayOf(1.0, 0.52, 0.12),
            1.0 to doubleArrayOf(0.80, 0.20, 0.06),
        ))
        val spin = Math.toRadians(32.0 + 11.0 * i)
        val rCore = mix(0.16, 0.055, u)
        val rHalo = mix(0.46, 0.18, u)
        val len1 = mix(0.92, 0.36, u)
        val len2 = len1 * 0.52
        val fade = 1.0 - 0.88 * u.pow(1.5)
        return Field { x, y ->
            val r = hypot(x, y)
            val core = exp(-(r / rCore).pow(2) * 1.6)
            val halo = 0.55 * exp(-(r / rHalo).pow(2) * 2.2)
            val s = streak(x, y, spin, len1, 0.030) + 0.75 * streak(x, y, spin + PI / 2 + 0.55, len2, 0.026)
            val w = (core * 1.2).coerceIn(0.0, 1.0)
            val a = (core * 1.1 + halo * 0.85 + s * 0.9).coerceIn(0.0, 1.0).pow(0.9)
            Px(mix(warm[0], 1.0, w), mix(warm[1], 1.0, w), mix(warm[2], 0.95, w), a * fade * edge(x, y))
        }
    }

    /** Thin streak centred on the origin along angle [ang]: gaussian across [w], tapering to nothing at +-[len]. */
    private fun streak(x: Double, y: Double, ang: Double, len: Double, w: Double): Double {
        val along = x * cos(ang) + y * sin(ang)
        val perp = -x * sin(ang) + y * cos(ang)
        val fall = (1.0 - abs(along) / len).coerceAtLeast(0.0).pow(1.6)
        return fall * exp(-(perp / w).pow(2))
    }

    // ---- smoke (cloud, baked grey) -------------------------------------------------------------------

    /**
     * A billowing puff: domain-warped fbm inside a blob that swells, whose noise turns about the centre (curl)
     * while an erosion threshold climbs, so the puff frays into wisps and dissolves. Light from the top left.
     */
    private fun smoke(t: Double, i: Int, n: Int): Field {
        val e = 1.0 - (1.0 - t).pow(2)
        val radius = mix(0.34, 0.80, e)
        val erode = mix(0.02, 0.55, smooth(0.15, 1.0, t))
        val rot = t * 1.5
        val alphaScale = 0.92 * (1.0 - 0.55 * smooth(0.5, 1.0, t))
        return Field { x, y ->
            val r = hypot(x, y)
            val ang = rot * (1.15 - r).coerceAtLeast(0.0)
            val c = cos(ang); val s = sin(ang)
            val px = x * c - y * s
            val py = x * s + y * c
            val warp = fbm(px * 1.7 + 3.1, py * 1.7 + t * 0.9, 5) - 0.5
            val detail = fbm(px * 3.6 - t * 0.7, py * 3.6 + 9.2, 9)
            val d = r + 0.46 * warp * radius
            val mask = (1.0 - smooth(0.0, radius, d)).pow(1.3)
            val dens = (mask * (0.30 + 1.25 * detail)).coerceIn(0.0, 1.0)
            val a = smooth(erode, erode + 0.40, dens) * alphaScale * edge(x, y)
            val lit = (0.60 + 0.30 * (detail - 0.5) * 2.0 - 0.22 * (x + y) * 0.5).coerceIn(0.35, 1.0)
            Px(lit, lit * 0.985, lit * 0.96, a)
        }
    }

    // ---- maths ---------------------------------------------------------------------------------------

    /** 1 inside, falling to 0 at the sprite border so no quad edge is ever visible. */
    private fun edge(x: Double, y: Double): Double = 1.0 - smooth(0.80, 0.98, hypot(x, y))

    private fun mix(a: Double, b: Double, f: Double): Double = a + (b - a) * f

    private fun smooth(e0: Double, e1: Double, v: Double): Double {
        val k = ((v - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return k * k * (3.0 - 2.0 * k)
    }

    private fun ramp(u: Double, stops: List<Pair<Double, DoubleArray>>): DoubleArray {
        val hi = stops.indexOfFirst { it.first >= u }.let { if (it < 0) stops.size - 1 else it }
        val lo = (hi - 1).coerceAtLeast(0)
        val (u0, c0) = stops[lo]
        val (u1, c1) = stops[hi]
        val f = if (u1 > u0) ((u - u0) / (u1 - u0)).coerceIn(0.0, 1.0) else 0.0
        return DoubleArray(3) { mix(c0[it], c1[it], f) }
    }

    /** Integer hash to 0..1; deterministic across JVMs (Int overflow wraps). */
    private fun hash(ix: Int, iy: Int, seed: Int): Double {
        var n = ix * 374761393 + iy * 668265263 + seed * 1274126177.toInt()
        n = (n xor (n ushr 13)) * 1274126177.toInt()
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777215.0
    }

    private fun vnoise(x: Double, y: Double, seed: Int): Double {
        val x0 = floor(x); val y0 = floor(y)
        val fx = x - x0; val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val ix = x0.toInt(); val iy = y0.toInt()
        val a = mix(hash(ix, iy, seed), hash(ix + 1, iy, seed), sx)
        val b = mix(hash(ix, iy + 1, seed), hash(ix + 1, iy + 1, seed), sx)
        return mix(a, b, sy)
    }

    private fun fbm(x: Double, y: Double, seed: Int): Double {
        var sum = 0.0; var amp = 0.5; var f = 1.0
        repeat(4) {
            sum += amp * vnoise(x * f, y * f, seed + it)
            f *= 2.0; amp *= 0.5
        }
        return sum / 0.9375
    }
}
