package com.astarworks.astera.itemart

import java.awt.image.BufferedImage

/** Four-tone colour ramp, ARGB: shadow, base, light, highlight. */
internal class Ramp(val dark: Int, val mid: Int, val light: Int, val hi: Int, val flat: Boolean = false) {
    fun tone(i: Int): Int = when (i) { 0 -> dark; 1 -> mid; 2 -> light; else -> hi }
}

internal data class P(val x: Double, val y: Double)

/**
 * A 32x32 paint surface. Shapes are painted as "parts" (one ramp each); [render] bevels every part
 * from a top-left light (edge pixels facing up/left get the light tone, down/right the shadow tone),
 * adds seeded dither, then an outline and an optional glow.
 */
internal class Sprite(private val seed: Long) {
    val size: Int = SIZE
    private val part = IntArray(SIZE * SIZE)
    private val ramps = ArrayList<Ramp>()
    private var glow: Glow? = null
    private val sparks = ArrayList<Triple<Int, Int, Int>>()

    private class Glow(val cx: Double, val cy: Double, val radius: Double, val color: Int)

    fun ramp(r: Ramp): Int { ramps.add(r); return ramps.size }

    fun poly(points: List<P>, id: Int) {
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (inside(points, x + 0.5, y + 0.5)) part[y * SIZE + x] = id
        }
    }

    fun rect(x0: Double, y0: Double, x1: Double, y1: Double, id: Int) =
        poly(listOf(P(x0, y0), P(x1, y0), P(x1, y1), P(x0, y1)), id)

    fun disc(cx: Double, cy: Double, r: Double, id: Int) {
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val dx = x + 0.5 - cx
            val dy = y + 0.5 - cy
            if (dx * dx + dy * dy <= r * r) part[y * SIZE + x] = id
        }
    }

    /** Thick segment; [w] is the full width in pixels. */
    fun line(a: P, b: P, w: Double, id: Int) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = Math.hypot(dx, dy).coerceAtLeast(1e-6)
        val nx = -dy / len * w / 2
        val ny = dx / len * w / 2
        poly(listOf(P(a.x + nx, a.y + ny), P(b.x + nx, b.y + ny), P(b.x - nx, b.y - ny), P(a.x - nx, a.y - ny)), id)
    }

    fun pixel(x: Int, y: Int, id: Int) {
        if (x in 0 until SIZE && y in 0 until SIZE) part[y * SIZE + x] = id
    }

    fun glow(cx: Double, cy: Double, radius: Double, color: Int) { glow = Glow(cx, cy, radius, color) }

    fun spark(x: Int, y: Int, color: Int) { sparks.add(Triple(x, y, color)) }

    private fun at(x: Int, y: Int): Int = if (x in 0 until SIZE && y in 0 until SIZE) part[y * SIZE + x] else 0

    private fun hash(x: Int, y: Int): Int {
        var h = seed * 0x9E3779B97F4A7C15uL.toLong() xor (x * 73856093L) xor (y * 19349663L)
        h = (h xor (h ushr 29)) * -0x40a7b892e31b1a47L
        return ((h xor (h ushr 32)) and 0x7fffffff).toInt()
    }

    fun render(): BufferedImage {
        val argb = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val p = at(x, y)
            if (p == 0) continue
            val r = ramps[p - 1]
            if (r.flat) { argb[y * SIZE + x] = r.mid; continue }
            val litEdge = at(x, y - 1) == 0 || at(x - 1, y) == 0
            val shadeEdge = at(x, y + 1) == 0 || at(x + 1, y) == 0
            var tone = 1
            if (litEdge && !shadeEdge) tone = 2
            if (shadeEdge && !litEdge) tone = 0
            if (litEdge && at(x, y - 1) == 0 && at(x - 1, y) == 0) tone = 3
            if (tone == 1) {
                val n = hash(x, y) % 100
                if (n < 7) tone = 2 else if (n < 13) tone = 0
            }
            argb[y * SIZE + x] = r.tone(tone)
        }
        // Outline: transparent pixels 4-adjacent to the sprite.
        val outlined = argb.copyOf()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (at(x, y) != 0) continue
            if (at(x - 1, y) != 0 || at(x + 1, y) != 0 || at(x, y - 1) != 0 || at(x, y + 1) != 0) {
                outlined[y * SIZE + x] = OUTLINE
            }
        }
        glow?.let { g ->
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                if (outlined[y * SIZE + x] != 0) continue
                val d = Math.hypot(x + 0.5 - g.cx, y + 0.5 - g.cy)
                if (d >= g.radius) continue
                val f = 1.0 - d / g.radius
                val alpha = (GLOW_ALPHA * f * f).toInt()
                if (alpha > 0) outlined[y * SIZE + x] = (alpha shl 24) or (g.color and 0xFFFFFF)
            }
        }
        for ((x, y, c) in sparks) {
            if (x in 0 until SIZE && y in 0 until SIZE && outlined[y * SIZE + x] ushr 24 < 0xFF) outlined[y * SIZE + x] = c
        }
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, SIZE, SIZE, outlined, 0, SIZE)
        return img
    }

    private fun inside(pts: List<P>, px: Double, py: Double): Boolean {
        var c = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[j]
            if ((a.y > py) != (b.y > py) && px < (b.x - a.x) * (py - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    companion object {
        const val SIZE: Int = 32
        const val OUTLINE: Int = 0xFF16111F.toInt()
        private const val GLOW_ALPHA = 150
    }
}
