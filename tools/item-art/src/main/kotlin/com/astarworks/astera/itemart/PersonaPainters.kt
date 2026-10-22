package com.astarworks.astera.itemart

/** Hand-drawn silhouettes of the persona weapons (ADR-0221): one painter per [PersonaArt]. */
internal enum class PersonaArt(val style: ModelStyle) {
    SHEARS(ModelStyle.HANDHELD), KATANA(ModelStyle.HANDHELD), VENDETTA_RIFLE(ModelStyle.GENERATED),
    GAUNTLET(ModelStyle.GENERATED), HAMMER(ModelStyle.HANDHELD), CHAIN_AXE(ModelStyle.HANDHELD),
    SIGIL_RIFLE(ModelStyle.GENERATED), ORRERY(ModelStyle.HANDHELD), EDICT(ModelStyle.GENERATED),
}

private const val R2 = 0.70710678118

/** Diagonal frame, same as [BladePainter]: u from the bottom-left grip towards the top-right, v across. */
private fun at(u: Double, v: Double) = P(3.5 + (u + v) * R2, 28.5 + (v - u) * R2)

private fun ramp(a: Long, b: Long, c: Long, d: Long, flat: Boolean = false) =
    Ramp(a.toInt(), b.toInt(), c.toInt(), d.toInt(), flat)

private fun flat(c: Long) = ramp(c, c, c, c, flat = true)

private val WHITE_STEEL = ramp(0xFF9AA6BC, 0xFFD6DEEC, 0xFFF2F6FC, 0xFFFFFFFF)
private val DARK = ramp(0xFF0B0C10, 0xFF1C1F27, 0xFF343A48, 0xFF565E72)
private val GOLD = ramp(0xFF7A4E08, 0xFFD39A18, 0xFFFFCC40, 0xFFFFF0A0)

internal object PersonaPainter {
    fun paint(s: Sprite, art: PersonaArt) = when (art) {
        PersonaArt.SHEARS -> shears(s)
        PersonaArt.KATANA -> katana(s)
        PersonaArt.VENDETTA_RIFLE -> vendettaRifle(s)
        PersonaArt.GAUNTLET -> gauntlet(s)
        PersonaArt.HAMMER -> hammer(s)
        PersonaArt.CHAIN_AXE -> chainAxe(s)
        PersonaArt.SIGIL_RIFLE -> sigilRifle(s)
        PersonaArt.ORRERY -> orrery(s)
        PersonaArt.EDICT -> edict(s)
    }

    /** Blade strip from [u0] to [u1] with half-width easing [w0]..[w1], bowed by [bow] (peak offset in v). */
    private fun strip(s: Sprite, u0: Double, u1: Double, w0: Double, w1: Double, bow: Double, id: Int) {
        val n = 14
        val top = ArrayList<P>()
        val bot = ArrayList<P>()
        for (i in 0..n) {
            val t = i / n.toDouble()
            val u = u0 + (u1 - u0) * t
            val w = w0 + (w1 - w0) * t
            val c = bow * t * t
            top.add(at(u, c + w))
            bot.add(at(u, c - w))
        }
        val pts = ArrayList<P>(top)
        pts.add(at(u1 + 2.2, bow))
        pts.addAll(bot.reversed())
        s.poly(pts, id)
    }

    private fun shears(s: Sprite) {
        val dark = s.ramp(DARK)
        val steel = s.ramp(WHITE_STEEL)
        val flower = s.ramp(ramp(0xFF8F7C85, 0xFFD5C1CB, 0xFFF2E6EC, 0xFFFFFFFF))
        // Two visibly separate tapered blades share a flower-shaped pivot.
        s.poly(listOf(P(14.0, 17.0), P(17.0, 15.0), P(27.0, 2.0), P(19.0, 16.0)), steel)
        s.poly(listOf(P(14.0, 17.0), P(13.0, 13.0), P(10.0, 1.0), P(17.0, 15.0)), steel)
        s.line(P(16.0, 16.0), P(25.5, 4.0), 1.0, dark)
        s.line(P(15.0, 15.0), P(11.0, 3.0), 1.0, dark)
        // Open angular handle loops, outlined rather than filled circles.
        for (pts in listOf(
            listOf(P(14.0, 17.0), P(9.0, 18.0), P(3.0, 25.0), P(7.0, 29.0), P(13.0, 23.0)),
            listOf(P(16.0, 17.0), P(20.0, 19.0), P(22.0, 27.0), P(17.0, 29.0), P(14.0, 23.0)),
        )) for (i in pts.indices) s.line(pts[i], pts[(i + 1) % pts.size], 1.6, dark)
        for ((x, y) in listOf(14.0 to 15.0, 17.0 to 15.0, 14.0 to 18.0, 17.0 to 18.0)) s.disc(x, y, 1.7, flower)
        s.disc(15.5, 16.5, 1.1, dark)
    }

    private fun katana(s: Sprite) {
        val wrap = s.ramp(ramp(0xFF0C1C44, 0xFF17306E, 0xFF264A9C, 0xFF4470D0))
        val thread = s.ramp(flat(0xFFE02838))
        val tsuba = s.ramp(DARK)
        val rim = s.ramp(flat(0xFFB8860B))
        val blade = s.ramp(WHITE_STEEL)
        val hamon = s.ramp(flat(0xFF9EB0D0))
        val bow = 2.2
        s.line(at(0.0, 0.0), at(5.6, 0.0), 2.1, wrap)
        for (u in listOf(1.0, 2.6, 4.2)) s.line(at(u, -1.1), at(u + 0.9, 1.1), 0.6, thread)
        val pc = at(-0.7, 0.0)
        s.disc(pc.x, pc.y, 1.5, thread)
        strip(s, 7.0, 30.0, 1.5, 1.1, bow, blade)
        // the hamon: a darker line along the spine side of the blade
        for (i in 0..13) {
            val u = 8.0 + i * 1.6
            val t = (u - 7.0) / 23.0
            val p = at(u, bow * t * t - 0.6)
            s.pixel(p.x.toInt(), p.y.toInt(), hamon)
        }
        // rectangular tsuba with a gold rim
        s.poly(listOf(at(6.0, -2.9), at(7.4, -2.9), at(7.4, 2.9), at(6.0, 2.9)), tsuba)
        s.line(at(6.7, -2.9), at(6.7, 2.9), 0.5, rim)
        s.glow(at(18.0, 1.0).x, at(18.0, 1.0).y, 11.0, 0xFFA0C0FF.toInt(), alpha = 55)
    }

    private fun vendettaRifle(s: Sprite) {
        val wood = s.ramp(ramp(0xFF2A190C, 0xFF4A2D16, 0xFF6A4524, 0xFF8A5E34))
        val dark = s.ramp(DARK)
        val steel = s.ramp(ramp(0xFF4C5568, 0xFF8C96A8, 0xFFC6CEDB, 0xFFF4F8FC))
        val crim = s.ramp(ramp(0xFF5A0818, 0xFFB01030, 0xFFE83060, 0xFFFF70A0))
        val orange = s.ramp(ramp(0xFFB84A08, 0xFFFF7A1A, 0xFFFFB040, 0xFFFFE080))
        val mag = s.ramp(flat(0xFFD020A0))
        s.poly(listOf(P(1.0, 15.0), P(9.0, 14.0), P(9.0, 19.5), P(5.5, 22.5), P(1.0, 22.5)), wood)
        s.poly(listOf(P(8.0, 18.5), P(11.5, 18.5), P(10.5, 24.5), P(7.5, 24.5)), wood)
        s.rect(9.0, 12.5, 17.0, 18.5, dark)
        s.rect(10.0, 14.0, 16.0, 15.2, crim)
        s.rect(13.0, 18.5, 14.5, 20.5, dark)
        s.rect(17.0, 14.2, 25.0, 17.2, wood)
        s.rect(17.0, 12.8, 25.5, 14.2, steel)
        s.rect(24.0, 11.4, 26.0, 14.2, dark)
        // bayonet blade past the muzzle
        s.poly(listOf(P(25.5, 11.0), P(29.0, 11.0), P(31.8, 12.8), P(25.5, 13.6)), steel)
        s.rect(25.5, 13.6, 27.0, 14.8, crim)
        s.rect(1.0, 13.6, 4.0, 15.0, mag)
        s.rect(11.0, 10.4, 15.0, 12.5, orange)
        s.glow(26.0, 12.5, 6.0, 0xFFFF7A1A.toInt(), alpha = 70)
    }

    private fun gauntlet(s: Sprite) {
        val steel = s.ramp(DARK)
        val plate = s.ramp(ramp(0xFF14161C, 0xFF2C303C, 0xFF4A5164, 0xFF747E96))
        val orange = s.ramp(ramp(0xFFB83A04, 0xFFFF7A1A, 0xFFFFB040, 0xFFFFF0A0))
        val vent = s.ramp(flat(0xFFFF9A30))
        val hot = s.ramp(flat(0xFFFFE070))
        // cuff and forearm
        s.poly(listOf(P(8.0, 31.0), P(24.0, 31.0), P(23.0, 22.0), P(9.0, 22.0)), steel)
        s.rect(8.0, 22.0, 24.0, 24.5, orange)
        // back of the fist
        s.poly(listOf(P(8.5, 22.0), P(9.5, 12.5), P(22.5, 12.5), P(23.5, 22.0)), plate)
        // four knuckle blocks, thumb on the left
        for (i in 0..3) s.rect(9.4 + i * 3.4, 6.0 + (if (i == 1 || i == 2) -1.0 else 0.0), 12.4 + i * 3.4, 13.0, plate)
        s.poly(listOf(P(3.5, 18.0), P(9.0, 14.5), P(9.5, 21.0), P(5.5, 21.5)), plate)
        // nozzle vents across the back of the hand and on the knuckles
        for (i in 0..2) s.rect(11.0, 15.0 + i * 2.2, 21.0, 16.2 + i * 2.2, vent)
        for (i in 0..3) s.rect(10.2 + i * 3.4, 5.4 + (if (i == 1 || i == 2) -1.0 else 0.0), 11.6 + i * 3.4, 7.0, orange)
        s.rect(13.0, 26.0, 19.0, 28.0, hot)
        s.glow(16.0, 8.0, 9.0, 0xFFFF7A1A.toInt(), alpha = 110)
        s.spark(8, 4, 0xFFFFB03A.toInt())
        s.spark(24, 3, 0xFFFF7A1A.toInt())
        s.spark(16, 1, 0xFFFFE066.toInt())
    }

    private fun hammer(s: Sprite) {
        val shaft = s.ramp(DARK)
        val gold = s.ramp(GOLD)
        val head = s.ramp(ramp(0xFF05060A, 0xFF14161E, 0xFF2A2E3A, 0xFF4A5064))
        val teal = s.ramp(ramp(0xFF0A5E5E, 0xFF14B0A8, 0xFF40E8D8, 0xFFB8FFF4))
        s.line(at(-0.5, 0.0), at(25.0, 0.0), 2.2, shaft)
        s.line(at(3.0, 0.0), at(7.5, 0.0), 2.8, gold)
        val pc = at(-1.0, 0.0)
        s.disc(pc.x, pc.y, 1.7, gold)
        // stake-shaped head: flat striking face on one side, tapering to a spike on the other
        s.poly(listOf(at(20.0, -4.2), at(27.0, -4.2), at(27.0, -1.5), at(25.0, 8.5), at(22.0, 8.5), at(20.0, -1.5)), head)
        s.poly(listOf(at(22.0, 8.5), at(25.0, 8.5), at(23.5, 12.0)), head)
        s.line(at(19.6, -4.4), at(27.4, -4.4), 1.3, gold)
        s.line(at(20.4, 0.6), at(26.6, 0.6), 1.0, gold)
        val gem = at(23.5, 3.5)
        s.disc(gem.x, gem.y, 1.3, teal)
        s.glow(gem.x, gem.y, 8.0, 0xFF40E8D8.toInt(), alpha = 55)
    }

    private fun chainAxe(s: Sprite) {
        val shaft = s.ramp(ramp(0xFF2A1A0E, 0xFF4B2F1C, 0xFF6E4629, 0xFF936139))
        val blood = s.ramp(ramp(0xFF4A0610, 0xFF8E0C20, 0xFFC0102A, 0xFFE84A5A))
        val chain = s.ramp(ramp(0xFF3A404C, 0xFF7A8496, 0xFFB8C2D4, 0xFFF0F4FA))
        val dark = s.ramp(DARK)
        val glint = s.ramp(flat(0xFFFFFFFF))
        s.line(at(-0.5, 0.0), at(25.0, 0.0), 2.0, shaft)
        // chain links winding around the haft
        for (i in 0..5) {
            val u = 3.2 + i * 2.5
            val v = if (i % 2 == 0) 1.1 else -1.1
            val c = at(u, v)
            s.disc(c.x, c.y, 1.25, chain)
        }
        // a loose chain hanging from the pommel
        val p0 = at(-1.0, 0.0)
        for (i in 0..2) s.disc(p0.x - 0.8 - i * 1.4, p0.y + 1.2 + i * 1.5, 0.95, chain)
        // axe head: bearded blade on the +v side, back spike on -v
        s.poly(listOf(at(19.0, 0.8), at(18.0, 5.0), at(18.6, 8.6), at(24.0, 9.4), at(27.0, 6.6), at(25.5, 0.8)), blood)
        s.poly(listOf(at(21.0, -0.8), at(26.0, -0.8), at(25.0, -4.4)), dark)
        s.line(at(18.2, 8.2), at(24.0, 9.0), 0.7, glint)
        val g = at(21.5, 5.6)
        s.pixel(g.x.toInt(), g.y.toInt(), glint)
        s.glow(at(22.0, 5.0).x, at(22.0, 5.0).y, 8.0, 0xFFC0102A.toInt(), alpha = 70)
    }

    private fun sigilRifle(s: Sprite) {
        val wood = s.ramp(ramp(0xFF1C100A, 0xFF35211A, 0xFF52352A, 0xFF74503E))
        val dark = s.ramp(DARK)
        val steel = s.ramp(ramp(0xFF2E3340, 0xFF5A6273, 0xFF8790A3, 0xFFB9C1D1))
        val gold = s.ramp(GOLD)
        val red = s.ramp(ramp(0xFF6A0A10, 0xFFC01820, 0xFFF04048, 0xFFFF9090))
        val lens = s.ramp(flat(0xFFFF4048))
        s.poly(listOf(P(1.0, 16.0), P(9.0, 15.0), P(9.0, 20.5), P(5.5, 23.5), P(1.0, 23.5)), wood)
        s.poly(listOf(P(8.0, 19.5), P(11.5, 19.5), P(10.5, 25.0), P(7.5, 25.0)), dark)
        s.rect(9.0, 13.5, 17.0, 19.5, dark)
        s.rect(10.0, 15.0, 16.0, 16.2, gold)
        s.rect(17.0, 14.6, 29.5, 16.8, steel)
        s.rect(17.0, 16.8, 22.0, 19.0, wood)
        // scope on two rings
        s.rect(11.0, 10.8, 13.0, 13.5, dark)
        s.rect(17.0, 10.8, 19.0, 13.5, dark)
        s.rect(8.5, 7.6, 22.0, 10.8, dark)
        s.rect(8.5, 8.2, 10.0, 10.2, lens)
        s.rect(20.6, 8.2, 22.0, 10.2, lens)
        // sigil disc near the muzzle
        s.disc(25.5, 15.7, 5.4, gold)
        s.disc(25.5, 15.7, 4.2, dark)
        s.disc(25.5, 15.7, 2.6, red)
        s.disc(25.5, 15.7, 1.0, gold)
        s.rect(24.9, 9.8, 26.1, 21.6, gold)
        s.rect(20.0, 15.1, 31.0, 16.3, steel)
        s.glow(25.5, 15.7, 8.0, 0xFFFFCC40.toInt(), alpha = 80)
    }

    private fun orrery(s: Sprite) {
        val wood = s.ramp(ramp(0xFF3A2412, 0xFF6B4423, 0xFF946033, 0xFFBA8548))
        val gold = s.ramp(GOLD)
        val ring = s.ramp(flat(0xFFFFB000))
        val ringHi = s.ramp(flat(0xFFFFE070))
        val orb = s.ramp(ramp(0xFF2A1060, 0xFF5A3AB0, 0xFF9A80F0, 0xFFE0D8FF))
        val shaftEnd = 21.0
        s.line(at(-1.0, 0.0), at(shaftEnd, 0.0), 2.0, wood)
        s.line(at(3.0, 0.0), at(6.0, 0.0), 2.6, gold)
        s.line(at(shaftEnd - 1.2, 0.0), at(shaftEnd, 0.0), 2.8, gold)
        val h = at(shaftEnd + 5.5, 0.0)
        s.disc(h.x, h.y, 2.7, orb)
        // three tilted armillary rings
        val rx = 7.2
        for ((ry, rot) in listOf(2.3 to 0.0, 2.8 to 1.05, 2.8 to -1.05)) {
            var a = 0.0
            while (a < Math.PI * 2) {
                val ex = Math.cos(a) * rx
                val ey = Math.sin(a) * ry
                val x = h.x + ex * Math.cos(rot) - ey * Math.sin(rot)
                val y = h.y + ex * Math.sin(rot) + ey * Math.cos(rot)
                s.pixel(x.toInt(), y.toInt(), if (ey < 0) ringHi else ring)
                a += 0.07
            }
        }
        s.disc(h.x, h.y, 1.6, orb)
        s.glow(h.x, h.y, 10.0, 0xFFFFB000.toInt(), alpha = 90)
        s.spark((h.x + 6).toInt(), (h.y - 4).toInt(), 0xFFFF3040.toInt())
        s.spark((h.x - 6).toInt(), (h.y + 3).toInt(), 0xFF40FF70.toInt())
        s.spark((h.x + 1).toInt(), (h.y - 8).toInt(), 0xFF40FF70.toInt())
        s.spark((h.x - 2).toInt(), (h.y + 8).toInt(), 0xFFFF3040.toInt())
    }

    private fun edict(s: Sprite) {
        val metal = s.ramp(ramp(0xFF514A36, 0xFF938A65, 0xFFC5BD96, 0xFFF1E6B8))
        val glove = s.ramp(WHITE_STEEL)
        val dark = s.ramp(DARK)
        // Paired closed white fists emerging from broad rectangular restraints.
        for (x in listOf(5.0, 18.0)) {
            s.rect(x, 15.0, x + 8.0, 25.0, dark)
            s.rect(x + 1.0, 16.0, x + 7.0, 23.0, metal)
            s.rect(x + 1.0, 7.0, x + 7.0, 16.0, glove)
            s.rect(x - 1.0, 11.0, x + 2.0, 16.0, glove)
            for (finger in 1..2) s.line(P(x + 1.0 + finger * 2, 7.0), P(x + 1.0 + finger * 2, 10.0), 0.7, dark)
            s.rect(x + 2.0, 19.0, x + 6.0, 21.0, dark)
        }
        // Three visible chain links connect the cuffs; no blade or crossguard.
        for ((x, y) in listOf(11.5 to 25.0, 15.5 to 27.0, 19.5 to 25.0)) {
            s.line(P(x - 2, y), P(x, y - 2), 1.0, metal)
            s.line(P(x, y - 2), P(x + 2, y), 1.0, metal)
            s.line(P(x + 2, y), P(x, y + 2), 1.0, metal)
            s.line(P(x, y + 2), P(x - 2, y), 1.0, metal)
        }
    }
}
