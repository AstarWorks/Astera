package com.astarworks.astera.itemart

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Code-drawn animated particle sprites (ADR-0239). Each painter evaluates an intensity field `v` in 0..1
 * per pixel and quantises it onto a 4-tone ramp derived from one base colour, so silhouettes are clean,
 * bright in the core and darker toward the rim, with binary alpha and no outline. Fully deterministic.
 *
 * Painters work in "32 px units" so a different `size` only rescales the picture.
 */
internal object ParticlePainters {

    val NAMES: Set<String> get() = PAINTERS.keys

    fun frames(name: String, count: Int, size: Int, color: Int): List<BufferedImage> {
        val painter = requireNotNull(PAINTERS[name]) { "unknown painter '$name' (known: ${PAINTERS.keys})" }
        val ramp = ramp(color)
        return (0 until count).map { i ->
            val t = if (count <= 1) 0.0 else i.toDouble() / (count - 1)
            val canvas = Canvas(size, ramp, smooth = size >= 128)
            painter(canvas, t, i, count)
            canvas.image()
        }
    }

    /** Light-to-dark ramp of [argb]: highlight (mostly white), light, the colour itself, a saturated shadow. */
    fun ramp(argb: Int): Ramp {
        val c = Color(argb)
        val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
        fun mix(a: Int, b: Int, f: Double) = (a + (b - a) * f).toInt().coerceIn(0, 255)
        fun toward(target: Int, f: Double) =
            Color(mix(c.red, target, f), mix(c.green, target, f), mix(c.blue, target, f)).rgb or OPAQUE
        val dark = Color.getHSBColor(hsb[0], min(1f, hsb[1] * 1.1f + 0.05f), hsb[2] * 0.5f).rgb or OPAQUE
        return Ramp(dark, argb or OPAQUE, toward(255, 0.45), toward(255, 0.86))
    }

    private const val OPAQUE = 0xFF000000.toInt()

    /** Paint surface in 32-px units; [put] takes an intensity and picks the ramp tone (or leaves the pixel clear). */
    class Canvas(val size: Int, private val ramp: Ramp, private val smooth: Boolean = false) {
        private val px = IntArray(size * size)
        private val k = 32.0 / size

        /** Evaluates [f] on a 2x2 sub-pixel grid when [smooth] is set: tone from the mean, alpha from the covered fraction (soft edges). */
        fun forEach(f: (x: Double, y: Double) -> Double) {
            if (!smooth) {
                for (py in 0 until size) for (pxl in 0 until size) put(pxl, py, f((pxl + 0.5) * k, (py + 0.5) * k))
                return
            }
            val offs = doubleArrayOf(0.25, 0.75)
            for (py in 0 until size) for (pxl in 0 until size) {
                var sum = 0.0
                var covered = 0
                for (oy in offs) for (ox in offs) {
                    val v = f((pxl + ox) * k, (py + oy) * k)
                    if (v >= DARK) { sum += v; covered++ }
                }
                if (covered == 0) continue
                val tone = when (val m = sum / covered) { in HI..9.0 -> 3; in LIGHT..HI -> 2; in MID..LIGHT -> 1; else -> 0 }
                val argb = ramp.tone(tone)
                val a = (255 * covered / 4)
                px[py * size + pxl] = (a shl 24) or (argb and 0xFFFFFF)
            }
        }

        fun put(x: Int, y: Int, v: Double) {
            if (x !in 0 until size || y !in 0 until size) return
            val tone = when {
                v >= HI -> 3
                v >= LIGHT -> 2
                v >= MID -> 1
                v >= DARK -> 0
                else -> return
            }
            px[y * size + x] = ramp.tone(tone)
        }

        /** Adds a single pixel at 32-px-unit coordinates, never lowering what is there. */
        fun dot(x: Double, y: Double, v: Double) {
            val ix = floor(x / k).toInt()
            val iy = floor(y / k).toInt()
            if (ix in 0 until size && iy in 0 until size && px[iy * size + ix] == 0) put(ix, iy, v)
        }

        fun image(): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, size, size, px, 0, size) }

        private companion object {
            const val HI = 0.8
            const val LIGHT = 0.55
            const val MID = 0.3
            const val DARK = 0.1
        }
    }

    private const val C = 16.0

    private fun ease(t: Double) = 1 - (1 - t.coerceIn(0.0, 1.0)).pow(3)

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)

    /** 4x4 Bayer threshold in (0, 1). */
    private fun bayer(x: Int, y: Int): Double {
        val m = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        return (m[(y and 3) * 4 + (x and 3)] + 0.5) / 16
    }

    /** Concave 4-point star distance: 1 on the rim of radius [r], 0 in the centre. */
    private fun star(dx: Double, dy: Double, r: Double, pinch: Double = 0.5): Double {
        if (r <= 0.0) return 1.0
        val a = abs(dx) / r
        val b = abs(dy) / r
        return (a.pow(pinch) + b.pow(pinch)).pow(1 / pinch)
    }

    private val PAINTERS: Map<String, (Canvas, Double, Int, Int) -> Unit> = mapOf(
        "gossamer_trail_wedge" to { c, _, _, _ ->
            c.forEach { x, y -> if (abs(y - 16.0) <= 16.0 * (0.63 + 0.37 * x / 32.0)) 1.0 else 0.0 }
        },
        "gossamer_ribbon" to ::gossamerRibbon,
        "gossamer_echo" to ::gossamerEcho, "gossamer_echo_mirror" to { c, t, _, n -> gossamerEcho(c, t, 1, n) }, "orrery_impact" to ::orreryImpact, "orb" to ::orb, "spark" to ::spark, "ring" to ::ring, "burst" to ::burst, "puff" to ::puff,
        "flame" to ::flame, "glint" to ::glint, "slash" to ::slash, "slice" to ::slice, "orbit" to ::orbit, "petal" to ::petal, "petal_blue" to ::petalBlue, "procuration_ribbon" to ::procurationRibbon, "crimson_orbit" to ::crimsonOrbit, "crimson_link" to ::crimsonLink,
        "arc" to ::arc, "rim" to ::rim, "crest" to ::crest, "ink" to ::ink,
    )

    /** Shallow crossing white loops at the first contact; open ends follow the visible orbital streaks. */
    private fun gossamerRibbon(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            var v = 0.0
            for (strand in 0..3) {
                val dx = (x - 16.0) / (14.0 - strand * 0.45)
                val dy = (y - 16.0 - (strand - 1.5) * 0.35 - (if (strand % 2 == 0) 0.14 else -0.14) * (x - 16.0)) / (2.5 + strand * 0.3)
                val a = (atan2(dy, dx) + PI * 3.0 + i * 0.35 + strand * 0.3) % (2.0 * PI)
                val tail = (a / 0.7).coerceIn(0.0, 1.0)
                val line = (1.0 - abs(hypot(dx, dy) - 1.0) / (0.035 + strand * 0.008)).coerceAtLeast(0.0)
                v = max(v, line * tail)
            }
            v
        }
    }

    /** Forward-leaning sword silhouette; a continuous outline instead of disconnected cube limbs. */
    private fun gossamerEcho(c: Canvas, t: Double, i: Int, n: Int) {
        fun segment(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double, radius: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val u = ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)
            return radius - hypot(x - (ax + dx * u.coerceIn(0.0, 1.0)), y - (ay + dy * u.coerceIn(0.0, 1.0)))
        }
        c.forEach { imageX, y ->
            val x = if (i == 1) 32.0 - imageX else imageX
            var d = 2.8 - hypot(x - 20, y - 8.5) // head
            val limbs = listOf(
                doubleArrayOf(18.0, 11.0, 12.0, 18.5, 2.5), // leaning torso
                doubleArrayOf(12.0, 19.0, 20.0, 22.0, 1.8),
                doubleArrayOf(20.0, 22.0, 24.0, 27.0, 1.2),
                doubleArrayOf(12.0, 19.0, 7.0, 25.0, 1.6),
                doubleArrayOf(7.0, 25.0, 3.0, 27.0, 1.0),
                doubleArrayOf(17.0, 12.0, 21.0, 17.0, 1.25),
                doubleArrayOf(21.0, 17.0, 25.0, 15.0, 0.9),
                doubleArrayOf(16.0, 13.0, 12.0, 12.0, 1.0),
                doubleArrayOf(12.0, 12.0, 9.0, 7.0, 0.8),
                doubleArrayOf(11.0, 17.0, 3.0, 20.0, 1.3), // trailing coat
                doubleArrayOf(24.0, 15.0, 31.0, 13.8, 0.28), // drawn blade
                doubleArrayOf(7.0, 18.5, 16.0, 20.0, 0.35), // separate scabbard
            )
            for (v in limbs) d = max(d, segment(x, y, v[0], v[1], v[2], v[3], v[4]))
            if (d < 0) 0.0 else if (d < 0.3) 0.9 else 0.55
        }
    }

    /** Interrupted double annulus with radial teeth, observed on Concentration's victim. */
    private fun orreryImpact(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - C
            val r = hypot(dx, dy)
            val a = atan2(dy, dx) + i * PI / 48
            val sector = (a * 12 / PI + 48) % 1.0
            val gap = sector < 0.10
            val outer = if (!gap) max(0.0, 1.0 - abs(r - 12.0) / 1.8) else 0.0
            val inner = if (sector > 0.18) max(0.0, 0.9 - abs(r - 9.2) / 0.65) else 0.0
            val tooth = if (sector in 0.38..0.58 && r in 10.0..14.8) 0.65 else 0.0
            max(max(outer, inner), tooth)
        }
    }

    /** Round charge light: broad white centre, narrow gold rim, no star arms. */
    private fun orb(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val r = hypot(x - C, y - C) / 15.0
            if (r > 1.0) 0.0 else (1.0 - r.pow(4)).coerceIn(0.0, 1.0)
        }
    }

    /** A 4-point star that pops open, hangs, then shrinks, with a small diagonal star and a white-hot core. */
    private fun spark(c: Canvas, t: Double, i: Int, n: Int) {
        val scale = if (t < 0.3) lerp(0.3, 1.0, ease(t / 0.3)) else lerp(1.0, 0.12, ((t - 0.3) / 0.7).pow(0.8))
        val r = 14.0 * scale
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - C
            val main = 1.0 - star(dx, dy, r)
            // The same star turned 45 degrees, half size.
            val rx = (dx + dy) * 0.7071
            val ry = (dy - dx) * 0.7071
            val diag = (1.0 - star(rx, ry, r * 0.5)) * 0.78
            val core = 1.0 - hypot(dx, dy) / (1.0 + 3.2 * scale)
            max(max(main, diag), core * 1.2)
        }
    }

    /** A shockwave: a ring that expands fast, slows, and thins out while dimming. */
    private fun ring(c: Canvas, t: Double, i: Int, n: Int) {
        val e = ease(t)
        val radius = lerp(3.0, 13.4, e)
        val half = lerp(2.3, 0.8, t)
        val peak = lerp(1.0, 0.62, t)
        c.forEach { x, y ->
            val d = hypot(x - C, y - C)
            val outer = (1.0 - abs(d - radius) / (half + 0.35)) * peak
            // A fainter echo ring trailing inside.
            val echoR = radius * 0.68
            val echo = (1.0 - abs(d - echoR) / (half * 0.55 + 0.3)) * peak * 0.5 * (1 - t)
            max(outer, echo)
        }
    }

    /** Eight streaks (long/short alternating) flying out of a flash that dies in the first frames. */
    private fun burst(c: Canvas, t: Double, i: Int, n: Int) {
        val e = ease(t)
        val rays = 8
        val width = lerp(1.9, 0.9, t)
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - C
            var v = (1.0 - hypot(dx, dy) / lerp(6.5, 0.5, (t / 0.45).coerceAtMost(1.0))).coerceAtLeast(0.0)
            for (k in 0 until rays) {
                val a = k * 2 * PI / rays + PI / 8
                val long = if (k % 2 == 0) 1.0 else 0.62
                val tail = lerp(2.0, 9.0, e) * long
                val head = min(lerp(5.0, 15.6, e) * long, 15.6)
                val ux = cos(a)
                val uy = sin(a)
                val along = dx * ux + dy * uy
                val across = abs(-dx * uy + dy * ux)
                if (along < tail || along > head) continue
                val f = (along - tail) / max(0.1, head - tail)
                // Fat bright head tapering to a thin tail.
                val w = width * (0.45 + 0.55 * f)
                val s = (1.0 - across / (w + 0.2)) * (0.5 + 0.55 * f) * lerp(1.0, 0.7, t)
                v = max(v, s)
            }
            v
        }
    }

    private val PUFFS = listOf(
        doubleArrayOf(0.0, 2.0, 6.0), doubleArrayOf(-6.0, 4.0, 4.6), doubleArrayOf(6.0, 4.5, 4.4),
        doubleArrayOf(-2.5, -3.0, 4.6), doubleArrayOf(3.5, -2.5, 4.0),
    )

    /** Dithered smoke: metaball cloud that swells, drifts up and dissolves through an ordered dither. */
    private fun puff(c: Canvas, t: Double, i: Int, n: Int) {
        val grow = lerp(0.55, 1.35, ease(t))
        val rise = lerp(0.0, -4.0, ease(t))
        c.size.let { sz ->
            val k = 32.0 / sz
            for (py in 0 until sz) for (pxl in 0 until sz) {
                val x = (pxl + 0.5) * k
                val y = (py + 0.5) * k
                var f = 0.0
                for ((j, p) in PUFFS.withIndex()) {
                    val r = p[2] * grow
                    val cx = C + p[0] * grow
                    val cy = C + p[1] * grow + rise - j * 0.4 * t * 3
                    val d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (r * r)
                    if (d2 < 1) f += (1 - d2).pow(2)
                }
                val density = f * lerp(1.0, 0.45, t)
                if (density < 0.1) continue
                // Lit from above: lighter toward the top of the cloud.
                val light = ((C - y) / 14.0) * 0.28
                val tone = density * 0.55 + light + 0.18
                val solid = density > 0.42
                val edge = (density - 0.1) / 0.32
                if (solid || edge > bayer(pxl, py)) c.put(pxl, py, tone.coerceIn(0.12, 0.78))
            }
        }
    }

    /** A flickering teardrop flame with a hot core and a few embers rising; frames loop. */
    private fun flame(c: Canvas, t: Double, i: Int, n: Int) {
        val phase = i.toDouble() / max(1, n) * 2 * PI
        val height = 21.0 + 2.2 * sin(phase * 2 + 0.6)
        val base = 28.0
        val width = 8.4 + 1.1 * sin(phase * 3 + 1.0)
        val sway = 2.2 * sin(phase)
        c.forEach { x, y ->
            val u = (base - y) / height
            if (u < 0 || u > 1.0) return@forEach 0.0
            // Belly low, pointed tip; tip leans with the sway.
            val half = width * sin(PI * min(1.0, u.pow(0.55) * 0.92 + 0.0)).pow(0.9) * (1 - u).pow(0.35)
            val cx = C + sway * u * u * 1.4 + 0.8 * sin(phase * 2 + u * 5) * u
            val off = abs(x - cx)
            if (off > half) return@forEach 0.0
            val edge = 1.0 - off / max(0.5, half)
            // Hotter near the bottom centre.
            val heat = edge * (1.0 - u * 0.55)
            min(1.0, heat * 1.15)
        }
        // Embers drifting up and sideways, wrapping around the loop.
        for (e in 0 until 3) {
            val f = ((i.toDouble() / max(1, n)) + e / 3.0) % 1.0
            val ex = C + (if (e % 2 == 0) -1 else 1) * (3.0 + e * 1.3) + 1.5 * sin(f * 2 * PI + e)
            val ey = base - 14.0 - f * 11.0
            c.dot(ex, ey, 0.85 - f * 0.55)
        }
    }

    /** A cross-shaped twinkle: thin long horizontal and vertical arms, opens and closes, sparkle dots around. */
    private fun glint(c: Canvas, t: Double, i: Int, n: Int) {
        val s = sin(PI * (0.08 + 0.84 * t)).pow(0.7)
        val rx = 16.0 * s
        val ry = 13.0 * s
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - C
            // Pixel centres sit half a pixel off the axis; shave that so a 1 px arm keeps its length.
            val ax = max(abs(dx) - 0.5, 0.0)
            val ay = max(abs(dy) - 0.5, 0.0)
            val arms = max(1.0 - star(ax, ay * rx / max(ry, 0.01), rx, 0.5), 0.0)
            val core = 1.0 - hypot(dx, dy) / (0.6 + 3.0 * s)
            val d45 = 1.0 - star((dx + dy) * 0.7071, (dy - dx) * 0.7071, rx * 0.34, 0.45)
            max(max(arms, core * 1.25), d45 * 0.7)
        }
        val orbit = listOf(doubleArrayOf(-9.0, -7.0), doubleArrayOf(8.0, 8.0), doubleArrayOf(9.5, -9.0))
        for ((j, o) in orbit.withIndex()) {
            val on = sin(PI * (t + j * 0.33)).pow(2)
            if (on > 0.55) c.dot(C + o[0], C + o[1], 0.5 + 0.5 * on)
        }
    }

    /** A crescent slash: arc swept from upper right to lower left, bright at the head, tail fading away. */
    private fun slash(c: Canvas, t: Double, i: Int, n: Int) = crescent(c, t, 3.6)

    /** Fine cutting ribbon, preserving the slash arc and sweep timing. */
    private fun slice(c: Canvas, t: Double, i: Int, n: Int) = crescent(c, t, 1.2)

    /** Asymmetric pointed fragment with a white face and blue rim; rotation comes from the VFX. */
    private fun petal(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val u = (y - 4.0) / 24.0
            if (u !in 0.0..1.0) return@forEach 0.0
            val width = 7.0 * sin(PI * u).pow(0.65) * (0.65 + 0.65 * u)
            val centre = 16.0 + 3.0 * (u - 0.5) + 0.8 * sin(3.0 * PI * u)
            val edge = 1.0 - abs(x - centre) / max(0.01, width)
            if (edge <= 0.0) 0.0 else min(1.0, 0.3 + edge * 1.4)
        }
    }

    /** Broader, chipped blue face seen alongside the pointed white petals. */
    private fun petalBlue(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val u = (y - 5.0) / 22.0
            if (u !in 0.0..1.0) return@forEach 0.0
            val width = 9.0 * sin(PI * u).pow(0.4) * (0.85 + 0.12 * sin(7.0 * PI * u))
            val centre = 16.0 - 3.0 * u + sin(2.0 * PI * u)
            val edge = 1.0 - abs(x - centre) / max(0.01, width)
            if (edge <= 0.0) 0.0 else 0.3 + 0.4 * edge
        }
    }

    /** Shallow pointed white/blue streak, observed during Procuration's horizontal passes. */
    private fun procurationRibbon(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val u = (x - 1.0) / 30.0
            if (u !in 0.0..1.0) return@forEach 0.0
            val centre = 18.0 - 7.0 * sin(PI * u) + 2.0 * u
            val halfWidth = 2.2 * sin(PI * u).pow(0.65) * (1.2 - 0.5 * u)
            val edge = 1.0 - abs(y - centre) / max(0.01, halfWidth)
            if (edge <= 0.0) 0.0 else min(1.0, 0.3 + edge * 1.5)
        }
    }

    /** Paired inward-open crescent blades on a dark spine, observed on the transformed chain. */
    private fun crimsonLink(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val mx = if (x > C) 32.0 - x else x
            val outer = hypot(mx - 10.0, y - C)
            val inner = hypot(mx - 12.6, y - C)
            if (outer < 7.0 && inner > 6.6) {
                val depth = min(7.0 - outer, inner - 6.6)
                min(1.0, 0.35 + depth * 0.8)
            } else if (abs(x - C) < 1.2 && y in 1.5..30.5) {
                0.2
            } else if (abs(y - C) < 1.0 && x in 5.0..27.0) {
                0.3
            } else 0.0
        }
    }

    /** Continuous white-core crimson ring; the VFX stretches and tilts it into crossed ellipses. */
    private fun crimsonOrbit(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val distance = abs(hypot(x - C, y - C) - 12.0)
            (1.0 - distance / 0.65).coerceAtLeast(0.0)
        }
    }

    /** A long, tapered orbit ribbon; the VFX timeline supplies its rotation. */
    private fun orbit(c: Canvas, t: Double, i: Int, n: Int) {
        val sweep = 230.0 * PI / 180.0
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - C
            val angle = (atan2(dy, dx) + 2 * PI) % (2 * PI)
            val u = angle / sweep
            if (u !in 0.0..1.0) return@forEach 0.0
            val halfWidth = 0.12 + 1.35 * sin(PI * u).pow(0.7)
            (1.0 - abs(hypot(dx, dy) - 11.5) / halfWidth) * (0.85 + 0.15 * sin(PI * u))
        }
    }

    private fun crescent(c: Canvas, t: Double, width: Double) {
        val cx = 1.0
        val cy = 1.0
        val radius = 22.0
        val a0 = 4.0 * PI / 180
        val a1 = 86.0 * PI / 180
        val head = ease(min(1.0, (t + 0.1) / 0.55))
        val tail = if (t < 0.4) 0.0 else ((t - 0.4) / 0.6).pow(1.1)
        c.forEach { x, y ->
            val dx = x - cx
            val dy = y - cy
            val rho = hypot(dx, dy)
            val s = (atan2(dy, dx) - a0) / (a1 - a0)
            if (s < tail || s > head || s < 0.0 || s > 1.0) return@forEach 0.0
            // Thickest mid-arc, pointed at both ends.
            val half = 0.4 + width * sin(PI * s).pow(0.8) * (1 - 0.35 * tail)
            val radial = 1.0 - abs(rho - radius - 0.6 * (s - 0.5)) / half
            val span = max(0.05, head - tail)
            val bright = 0.5 + 0.5 * ((s - tail) / span)
            radial * bright * lerp(1.0, 0.8, t) + (if (radial > 0.6) 0.2 else 0.0) * bright
        }
    }
    /** A wide tapered arc over the top (needle tips), the head racing round, then the tail eating it; bright at the head. */
    private fun arc(c: Canvas, t: Double, i: Int, n: Int) {
        val a0 = PI * 1.0
        val a1 = PI * 2.0
        val head = ease(min(1.0, (t + 0.08) / 0.5))
        val tail = if (t < 0.55) 0.0 else ((t - 0.55) / 0.45).pow(1.2)
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - 17.0
            val rho = hypot(dx * 1.0, dy)
            val s = (atan2(dy, dx).let { if (it < 0) it + 2 * PI else it } - a0) / (a1 - a0)
            if (s < tail || s > head || s < 0.0 || s > 1.0) return@forEach 0.0
            val w = ((s - tail) / max(0.05, head - tail)).coerceIn(0.0, 1.0)
            val half = 0.35 + 2.7 * sin(PI * w).pow(0.85) * (1 - 0.3 * tail)
            val radial = 1.0 - abs(rho - 14.5) / half
            val bright = 0.45 + 0.55 * ((s - tail) / max(0.05, head - tail))
            radial * bright + (if (radial > 0.7) 0.25 else 0.0) * bright
        }
    }

    /** An amorphous flat flash: a few long horizontal lens-shaped bands with pointed, uneven ends round a ragged core. */
    private fun ink(c: Canvas, t: Double, i: Int, n: Int) {
        val grow = if (t < 0.25) ease(t / 0.25) else 1.0
        val fade = if (t < 0.55) 1.0 else (1.0 - (t - 0.55) / 0.45).coerceAtLeast(0.0)
        val bands = listOf(
            doubleArrayOf(-9.0, 15.5, 1.7, -2.0), doubleArrayOf(-5.0, 14.0, 2.4, 1.0), doubleArrayOf(-1.0, 15.8, 3.0, -1.0),
            doubleArrayOf(3.5, 12.5, 2.2, 3.0), doubleArrayOf(7.5, 14.5, 1.6, -3.0), doubleArrayOf(11.0, 9.0, 1.2, 2.0),
        )
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - C
            var v = 0.0
            for ((bi, b) in bands.withIndex()) {
                val len = b[1] * grow * fade
                val cx = b[3] * grow
                val u = (dx - cx) / max(len, 0.01)
                if (abs(u) >= 1.0) continue
                val wob = 0.35 * sin(dx * 0.7 + bi * 2.1 + i)
                val half = b[2] * (1.0 - abs(u).pow(1.6)) * (1.0 - 0.3 * fade.let { 1 - it })
                val d = 1.0 - abs(dy - b[0] - wob) / max(half, 0.01)
                if (d > 0) v = max(v, if (d > 0.55) 0.62 else 0.42)
            }
            val core = 1.0 - hypot(dx * 0.7, dy * 1.2) / (8.5 * grow * fade + 0.01)
            if (core > 0 && sin(dx * 1.3 + dy * 0.9 + i) > -0.7) v = max(v, if (core > 0.45) 0.66 else 0.4)
            v
        }
    }

    /** A heater-shield crest: a thin outline pointed at the bottom and an inner line, strokes taper to the corners. */
    private fun crest(c: Canvas, t: Double, i: Int, n: Int) {
        val grow = if (n <= 1) 1.0 else ease(min(1.0, t / 0.3))
        c.forEach { x, y ->
            val dx = (x - C) / (11.0 * grow + 0.01)
            val dy = (y - C) / (14.5 * grow + 0.01)
            // Shield silhouette: flat top at dy=-1, sides bow out, point at dy=+1.
            val halfW = if (dy < -0.35) 1.0 - 0.05 * (dy + 1) else (1.0 - ((dy + 0.35) / 1.35).pow(1.6)) * 0.97
            if (dy < -1.0 || dy > 1.0 || halfW <= 0.0) return@forEach 0.0
            val edge = abs(abs(dx) - halfW)
            val outer = if (edge < 0.11) 0.75 else 0.0
            val top = if (dy < -0.92 && abs(dx) < halfW) 0.75 else 0.0
            val innerHalf = halfW * 0.72
            val inner = if (abs(abs(dx) - innerHalf) < 0.06 && dy > -0.8 && dy < 0.75) 0.5 else 0.0
            val spine = if (abs(dx) < 0.045 && dy > -0.75 && dy < 0.7) 0.45 else 0.0
            max(max(outer, top), max(inner, spine))
        }
    }

    /** A static tapered half ring (needle tips, brighter on the inside edge) for persistent outlines such as a barrier. */
    private fun rim(c: Canvas, t: Double, i: Int, n: Int) {
        c.forEach { x, y ->
            val dx = x - C
            val dy = y - 17.0
            val rho = hypot(dx, dy)
            val s = (atan2(dy, dx).let { if (it < 0) it + 2 * PI else it } - PI) / PI
            if (s < 0.0 || s > 1.0) return@forEach 0.0
            val half = 0.3 + 1.7 * sin(PI * s).pow(0.85)
            val radial = 1.0 - abs(rho - 14.5) / half
            radial * (0.7 + 0.3 * (1.0 - abs(rho - 14.5) / half).coerceIn(0.0, 1.0))
        }
    }
}
