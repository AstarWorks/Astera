package com.astarworks.astera.itemart

import java.util.Random

private const val INV_SQRT2 = 0.70710678118

/** Diagonal item frame: u runs from the bottom-left grip towards the top-right, v across it. */
private fun at(u: Double, v: Double, ox: Double = 3.5, oy: Double = 28.5) =
    P(ox + (u + v) * INV_SQRT2, oy + (v - u) * INV_SQRT2)


private fun shade(argb: Int, f: Double): Int {
    fun ch(s: Int) = ((argb shr s and 0xFF) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal object BladePainter {
    fun paint(s: Sprite, d: Design.Blade) {
        val grip = s.ramp(d.grip)
        val guard = s.ramp(d.guard)
        val blade = s.ramp(d.blade)
        val pommel = s.ramp(d.guard)
        val gripLen = 4.5
        val guardLen = 1.9
        // grip
        s.poly(listOf(at(0.0, -1.1), at(gripLen, -1.1), at(gripLen, 1.1), at(0.0, 1.1)), grip)
        // pommel
        val pc = at(-0.9, 0.0)
        s.disc(pc.x, pc.y, 1.7, pommel)
        // blade: shoulders then taper to a tip
        val b0 = gripLen + guardLen
        val tip = b0 + d.length
        s.poly(
            listOf(
                at(b0, -d.halfWidth), at(tip - d.halfWidth * 1.7, -d.halfWidth),
                at(tip, 0.0), at(tip - d.halfWidth * 1.7, d.halfWidth), at(b0, d.halfWidth),
            ),
            blade,
        )
        if (d.fuller) {
            val fuller = s.ramp(Ramp(d.blade.dark, d.blade.dark, d.blade.dark, d.blade.dark, flat = true))
            s.poly(listOf(at(b0 + 0.5, -0.45), at(tip - d.halfWidth * 2.6, -0.45), at(tip - d.halfWidth * 2.6, 0.45), at(b0 + 0.5, 0.45)), fuller)
            val edge = s.ramp(Ramp(d.blade.hi, d.blade.hi, d.blade.hi, d.blade.hi, flat = true))
            s.line(at(b0 + 0.8, -d.halfWidth + 0.35), at(tip - d.halfWidth * 2.2, -d.halfWidth + 0.35), 0.7, edge)
        }
        // cross guard drawn last so it overlaps the blade root
        s.poly(listOf(at(gripLen, -d.guardHalfWidth), at(gripLen + guardLen, -d.guardHalfWidth), at(gripLen + guardLen, d.guardHalfWidth), at(gripLen, d.guardHalfWidth)), guard)
        // guard tips curl slightly
        val l = at(gripLen + guardLen + 0.4, -d.guardHalfWidth)
        val r = at(gripLen + guardLen + 0.4, d.guardHalfWidth)
        s.disc(l.x, l.y, 0.9, guard)
        s.disc(r.x, r.y, 0.9, guard)
        // faint enchant halo around the blade and a glint at the tip
        val mid = at(b0 + d.length * 0.5, 0.0)
        d.halo?.let { s.glow(mid.x, mid.y, d.length * 0.55, it, alpha = 70) }
        val gl = at(tip + 1.5, 2.5)
        s.spark(gl.x.toInt(), gl.y.toInt(), 0xFFFFFFFF.toInt())
        s.spark(gl.x.toInt() + 1, gl.y.toInt(), 0xCCFFFFFF.toInt())
        s.spark(gl.x.toInt(), gl.y.toInt() - 1, 0xCCFFFFFF.toInt())
    }
}

internal object StaffPainter {
    fun paint(s: Sprite, e: Element, rnd: Random) {
        val wood = s.ramp(Ramp(0xFF3A2412.toInt(), 0xFF6B4423.toInt(), 0xFF946033.toInt(), 0xFFBA8548.toInt()))
        val metal = s.ramp(Ramp(0xFF6B4A0C.toInt(), 0xFFC4901C.toInt(), 0xFFF2C94C.toInt(), 0xFFFFF0A0.toInt()))
        val shaftEnd = 23.0
        s.line(at(-1.0, 0.0), at(shaftEnd, 0.0), 2.0, wood)
        s.line(at(3.0, 0.0), at(6.0, 0.0), 2.6, metal) // grip wrap band
        s.line(at(shaftEnd - 1.6, 0.0), at(shaftEnd, 0.0), 2.8, metal) // collar
        val head = at(shaftEnd + 4.0, 0.0)
        when (e) {
            Element.FIRE -> fire(s, head, metal, e)
            Element.FROST, Element.ARCANE -> crystal(s, head, metal, e)
            Element.THUNDER -> thunder(s, head, metal, e)
            Element.VERDANT -> verdant(s, head, e)
        }
        s.glow(head.x, head.y, 9.0, e.glow)
        // seeded sparks around the head
        repeat(5) {
            val a = rnd.nextDouble() * Math.PI * 2
            val dist = 7.5 + rnd.nextDouble() * 3.0
            val x = (head.x + Math.cos(a) * dist).toInt()
            val y = (head.y + Math.sin(a) * dist).toInt()
            s.spark(x, y, e.spark[rnd.nextInt(e.spark.size)])
        }
    }

    private fun prongs(s: Sprite, head: P, metal: Int, spread: Double) {
        val base = at(22.0, 0.0)
        s.line(base, P(head.x - spread, head.y + 2.5), 1.3, metal)
        s.line(base, P(head.x + spread, head.y + 2.5), 1.3, metal)
    }

    private fun fire(s: Sprite, h: P, metal: Int, e: Element) {
        prongs(s, h, metal, 3.6)
        val outer = s.ramp(e.deep)
        val mid = s.ramp(e.core)
        val inner = s.ramp(Ramp(0xFFFFA033.toInt(), 0xFFFFC94D.toInt(), 0xFFFFE680.toInt(), 0xFFFFFFC0.toInt()))
        fun flame(k: Double, id: Int, dy: Double) = s.poly(
            listOf(
                P(h.x, h.y - 8.0 * k + dy), P(h.x + 1.6 * k, h.y - 4.4 * k + dy), P(h.x + 3.6 * k, h.y - 2.4 * k + dy),
                P(h.x + 4.0 * k, h.y + 1.0 * k + dy), P(h.x + 2.6 * k, h.y + 3.8 * k + dy), P(h.x, h.y + 4.8 * k + dy),
                P(h.x - 2.6 * k, h.y + 3.8 * k + dy), P(h.x - 4.0 * k, h.y + 1.0 * k + dy), P(h.x - 3.2 * k, h.y - 2.6 * k + dy),
                P(h.x - 1.6 * k, h.y - 3.8 * k + dy), P(h.x - 1.2 * k, h.y - 6.0 * k + dy),
            ),
            id,
        )
        flame(1.0, outer, 0.0)
        flame(0.72, mid, 0.9)
        flame(0.42, inner, 1.6)
    }

    private fun crystal(s: Sprite, h: P, metal: Int, e: Element) {
        prongs(s, h, metal, 3.0)
        val dark = s.ramp(e.deep)
        val body = s.ramp(e.core)
        val shine = s.ramp(Ramp(e.core.hi, e.core.hi, e.core.hi, e.core.hi, flat = true))
        fun p(x: Double, y: Double) = P(h.x + x, h.y + y)
        s.poly(listOf(p(-6.2, -2.6), p(-4.6, -5.5), p(-3.6, -1.0), p(-4.8, 1.6)), dark) // left shard
        s.poly(listOf(p(6.2, -2.2), p(4.6, -5.0), p(3.6, -0.5), p(4.8, 2.0)), dark) // right shard
        s.poly(listOf(p(0.0, -8.8), p(3.6, -3.4), p(3.1, 3.8), p(0.0, 6.8), p(-3.1, 3.8), p(-3.6, -3.4)), body)
        s.poly(listOf(p(0.0, -8.8), p(-3.6, -3.4), p(-3.1, 3.8), p(0.0, 6.8)), dark)
        s.poly(listOf(p(0.0, -8.8), p(0.8, -3.0), p(0.0, 6.8), p(0.4, 3.0)), body)
        s.poly(listOf(p(1.0, -6.0), p(2.2, -3.0), p(1.8, 0.0), p(1.0, -1.0)), shine)
    }

    private fun thunder(s: Sprite, h: P, metal: Int, e: Element) {
        prongs(s, h, metal, 4.4)
        val rim = s.ramp(Ramp(e.deep.dark, e.deep.dark, e.deep.dark, e.deep.dark, flat = true))
        val dark = s.ramp(e.deep)
        val orb = s.ramp(e.core)
        val bolt = s.ramp(Ramp(0xFFFFD21F.toInt(), 0xFFFFF04D.toInt(), 0xFFFFFFA0.toInt(), 0xFFFFFFFF.toInt(), flat = true))
        s.disc(h.x, h.y, 5.4, rim)
        s.disc(h.x, h.y, 4.7, dark)
        s.disc(h.x - 0.6, h.y - 0.6, 3.9, orb)
        val pts = listOf(P(h.x + 1.8, h.y - 4.4), P(h.x - 1.2, h.y - 0.4), P(h.x + 1.2, h.y - 0.4), P(h.x - 1.8, h.y + 4.4))
        for (i in 0 until pts.size - 1) s.line(pts[i], pts[i + 1], 1.5, bolt)
    }

    private fun verdant(s: Sprite, h: P, e: Element) {
        val vine = s.ramp(Ramp(0xFF174A22.toInt(), 0xFF2A8A38.toInt(), 0xFF3FB04A.toInt(), 0xFF8EE36B.toInt()))
        val leafDark = s.ramp(e.deep)
        val leaf = s.ramp(e.core)
        val orb = s.ramp(Ramp(0xFFB8F59A.toInt(), 0xFFDDFFC2.toInt(), 0xFFF4FFE6.toInt(), 0xFFFFFFFF.toInt(), flat = true))
        fun p(x: Double, y: Double) = P(h.x + x, h.y + y)
        s.line(at(20.0, 0.0), p(-2.4, 2.0), 1.3, vine)
        s.line(at(21.5, 0.0), p(2.6, 2.0), 1.3, vine)
        s.poly(listOf(p(-8.0, -3.0), p(-3.6, -5.2), p(-1.6, -0.6), p(-5.8, 1.2)), leafDark) // left leaf
        s.poly(listOf(p(8.0, -2.4), p(3.6, -4.8), p(1.6, -0.4), p(5.8, 1.6)), leafDark) // right leaf
        s.poly(listOf(p(0.0, -9.0), p(3.4, -4.6), p(0.0, 0.6), p(-3.4, -4.6)), leaf) // top leaf
        s.line(p(0.0, -8.0), p(0.0, -1.0), 0.7, leafDark)
        s.disc(h.x, h.y + 0.6, 2.7, vine)
        s.disc(h.x, h.y + 0.6, 1.8, orb)
    }
}

internal object GunPainter {
    fun paint(s: Sprite, d: Design.Gun, rnd: Random) {
        val metal = s.ramp(Ramp(0xFF2A2E38.toInt(), 0xFF565E70.toInt(), 0xFF8791A6.toInt(), 0xFFC4CCDB.toInt()))
        val dark = s.ramp(Ramp(0xFF14161C.toInt(), 0xFF2C3039.toInt(), 0xFF454B58.toInt(), 0xFF6B7384.toInt()))
        val grip = s.ramp(Ramp(0xFF2A1A10.toInt(), 0xFF4B2F1C.toInt(), 0xFF6E4629.toInt(), 0xFF936139.toInt()))
        val wood = s.ramp(listOf(
            Ramp(0xFF3E2814.toInt(), 0xFF70491F.toInt(), 0xFF9A6A33.toInt(), 0xFFC49355.toInt()),
            Ramp(0xFF4A2A18.toInt(), 0xFF7E4A2A.toInt(), 0xFFA8693E.toInt(), 0xFFCF9162.toInt()),
            Ramp(0xFF3A2C1A.toInt(), 0xFF68502E.toInt(), 0xFF8E7244.toInt(), 0xFFB89A66.toInt()),
        )[rnd.nextInt(3)])
        val accent = s.ramp(Ramp(shade(d.accent, 0.55), d.accent, shade(d.accent, 1.3), shade(d.accent, 1.7)))
        val muzzle = rnd.nextInt(3) - 1.0 // seeded +-1px barrel length
        val gx = when (d.kind) { GunKind.PISTOL -> 16.0 to 12.5; GunKind.RIFLE -> 21.0 to 9.0; GunKind.SHOTGUN -> 11.0 to 13.8 }
        s.glow(gx.first, gx.second, 6.5, d.accent, alpha = 90)
        when (d.kind) {
            GunKind.PISTOL -> {
                s.rect(8.0, 15.0, 20.0, 17.5, dark)
                s.poly(listOf(P(9.0, 15.0), P(15.0, 15.0), P(14.2, 26.0), P(8.0, 26.0)), grip)
                s.rect(7.8, 25.0, 14.4, 27.0, dark)
                s.rect(16.0, 17.2, 21.0, 18.4, dark)
                s.rect(19.8, 15.0, 21.0, 18.4, dark)
                s.rect(7.0, 10.0, 25.0 + muzzle, 15.0, metal)
                s.rect(25.0 + muzzle, 11.0, 27.5 + muzzle, 14.0, dark)
                s.rect(10.0, 12.0, 22.0, 13.2, accent)
                s.rect(8.0, 8.8, 10.0, 10.0, dark)
                s.rect(23.0 + muzzle, 8.8, 24.8 + muzzle, 10.0, dark)
            }
            GunKind.RIFLE -> {
                s.poly(listOf(P(2.0, 12.5), P(10.0, 12.0), P(10.0, 18.0), P(5.5, 21.5), P(2.0, 21.5)), wood)
                s.poly(listOf(P(10.0, 17.0), P(13.5, 17.0), P(12.0, 24.0), P(8.5, 24.0)), grip)
                s.rect(12.0, 17.0, 16.0, 23.0, dark)
                s.rect(10.0, 11.0, 19.0, 17.0, dark)
                s.rect(19.0, 12.0, 30.0 + minOf(muzzle, 0.0), 14.2, metal)
                s.rect(19.0, 11.5, 26.0, 15.5, wood)
                s.rect(11.0, 13.2, 18.0, 14.4, accent)
                s.rect(11.8, 9.6, 13.0, 11.0, dark)
                s.rect(18.6, 9.6, 19.8, 11.0, dark)
                s.rect(11.0, 6.6, 21.5, 9.8, dark)
                s.disc(21.6, 8.2, 1.4, accent)
                s.rect(28.0 + minOf(muzzle, 0.0), 10.8, 29.2 + minOf(muzzle, 0.0), 12.0, dark)
            }
            GunKind.SHOTGUN -> {
                s.poly(listOf(P(1.0, 12.0), P(8.5, 11.0), P(8.5, 19.0), P(4.0, 23.5), P(1.0, 23.5)), wood)
                s.poly(listOf(P(8.0, 17.0), P(11.5, 17.0), P(10.0, 22.5), P(7.0, 22.5)), wood)
                s.rect(8.0, 10.0, 15.0, 17.0, dark)
                s.rect(13.0, 13.4, 28.0 + muzzle, 15.2, dark)
                s.rect(12.0, 10.4, 30.0 + muzzle, 13.4, metal)
                s.rect(17.0, 14.0, 25.0, 18.2, wood)
                for (x in listOf(18.4, 20.4, 22.4)) s.rect(x, 14.6, x + 0.6, 17.6, grip)
                s.rect(8.6, 13.0, 13.4, 14.6, accent)
                s.rect(29.0 + muzzle, 9.4, 30.2 + muzzle, 10.6, dark)
            }
        }
    }

    private fun shade(argb: Int, f: Double): Int {
        fun ch(sh: Int) = ((argb shr sh and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
