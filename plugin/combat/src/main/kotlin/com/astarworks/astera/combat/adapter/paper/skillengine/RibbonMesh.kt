package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge

/**
 * A trail ribbon built from the history of one point and a root point (ADR-0301). Each tick contributes a cross-section
 * (root, tip); consecutive cross-sections form one parallelogram [Segment]. Pure maths, no Bukkit.
 */
public class RibbonMesh {

    private class Section(val root: Vec3, val tip: Vec3)

    /** One parallelogram: [centre], edge vectors [u] (travel) and [v] (root to tip), unit [normal]. */
    public class Segment(public val centre: Vec3, public val u: Vec3, public val v: Vec3, public val normal: Vec3)

    private var last: Section? = null
    private var lastShaped: Section? = null

    /** Which part of a shaped trail a [Piece] belongs to; [Piece.index] is the band number for [PieceKind.BAND]. */
    public enum class PieceKind { BAND, EDGE, RIM }

    /** One quad of a shaped trail. */
    public class Piece(public val kind: PieceKind, public val index: Int, public val segment: Segment)

    /** Radial band `from..to` as fractions of the root-to-tip segment. */
    public class Band(public val from: Double, public val to: Double)

    /**
     * Shaped push (ADR-0301 trail bands): the step from the previous cross-section is split so the root-to-tip segment
     * turns at most [stepDeg] per quad (at most 12 splits), and every split yields one quad per band plus the edge
     * ([edgeWidth] blocks, centred on the tip) and the rim ([rimWidth] blocks outside the tip) when given.
     */
    public fun pushShaped(root: Vec3, tip: Vec3, bands: List<Band>, edgeWidth: Double?, rimWidth: Double?, stepDeg: Double): List<Piece> {
        val now = Section(root, tip)
        val before = lastShaped
        lastShaped = now
        if (before == null) return emptyList()
        val a = before.tip - before.root
        val b = now.tip - now.root
        val cos = if (a.lengthSquared() < 1e-12 || b.lengthSquared() < 1e-12) 1.0 else (a.dot(b) / (a.length() * b.length())).coerceIn(-1.0, 1.0)
        val steps = Math.ceil(Math.toDegrees(Math.acos(cos)) / stepDeg - 1e-9).toInt().coerceIn(1, MAX_SPLITS)
        val out = ArrayList<Piece>()
        for (k in 0 until steps) {
            val s0 = blend(before, now, k.toDouble() / steps)
            val s1 = blend(before, now, (k + 1.0) / steps)
            fun quad(kind: PieceKind, index: Int, f0: Double, f1: Double, relative: Boolean) {
                val q = if (relative) {
                    quadOf(at(s0, f0), at(s0, f1), at(s1, f0), at(s1, f1))
                } else {
                    val d0 = (s0.tip - s0.root).let { if (it.lengthSquared() < 1e-12) it else it.normalize() }
                    val d1 = (s1.tip - s1.root).let { if (it.lengthSquared() < 1e-12) it else it.normalize() }
                    quadOf(s0.tip + d0 * f0, s0.tip + d0 * f1, s1.tip + d1 * f0, s1.tip + d1 * f1)
                }
                if (q != null) out += Piece(kind, index, q)
            }
            bands.forEachIndexed { i, band -> quad(PieceKind.BAND, i, band.from, band.to, true) }
            if (edgeWidth != null) quad(PieceKind.EDGE, 0, -edgeWidth / 2.0, edgeWidth / 2.0, false)
            if (rimWidth != null) quad(PieceKind.RIM, 0, 0.0, rimWidth, false)
        }
        return out
    }

    private fun blend(a: Section, b: Section, t: Double) = Section(a.root + (b.root - a.root) * t, a.tip + (b.tip - a.tip) * t)

    private fun at(s: Section, f: Double): Vec3 = s.root + (s.tip - s.root) * f

    /** Parallelogram from the radial edge `a0-a1` and its later position `b0-b1`; null when it has no area. */
    private fun quadOf(a0: Vec3, a1: Vec3, b0: Vec3, b1: Vec3): Segment? {
        val u = ((b0 - a0) + (b1 - a1)) * 0.5
        val v = ((a1 - a0) + (b1 - b0)) * 0.5
        val n = u.cross(v)
        if (u.lengthSquared() < 1e-10 || v.lengthSquared() < 1e-10 || n.lengthSquared() < 1e-14) return null
        return Segment((a0 + a1 + b0 + b1) * 0.25, u, v, n.normalize())
    }

    /** Adds this tick's cross-section; returns the segment from the previous one, or null (first point, or no movement). */
    public fun push(root: Vec3, tip: Vec3): Segment? {
        val now = Section(root, tip)
        val before = last
        last = now
        if (before == null) return null
        val u = ((now.root - before.root) + (now.tip - before.tip)) * 0.5
        val v = ((before.tip - before.root) + (now.tip - now.root)) * 0.5
        val n = u.cross(v)
        if (u.lengthSquared() < 1e-10 || v.lengthSquared() < 1e-10 || n.lengthSquared() < 1e-14) return null
        val centre = (before.root + before.tip + now.root + now.tip) * 0.25
        return Segment(centre, u, v, n.normalize())
    }

    /** How a [Piece] is drawn: a translucent text quad ([SOFT]) or an opaque block slab ([SLAB], fades by shrinking). */
    public enum class Draw { SOFT, SLAB }

    /** One display the gateway spawns for a [Piece]; [band] is the band of a SOFT / SLAB band piece, [back] the reversed face of a double-sided soft quad. */
    public class Drawn(public val draw: Draw, public val piece: Piece, public val block: String?, public val back: Boolean)

    /** Local axes of a block slab on a segment: x = [ux] (root to tip), y = [cy] (travel across the blade), z = [n]. */
    public class SlabAxes(public val ux: Vec3, public val cy: Vec3, public val n: Vec3, public val radial: Double, public val chord: Double)

    public companion object {
        private const val MAX_SPLITS: Int = 12

        /** Slabs / quads are this much longer than the interval they cover so neighbours overlap. */
        public const val OVERLAP: Double = 1.06

        /** Thickness of a crisp band slab. */
        public const val SLAB_THICKNESS: Double = 0.02

        /**
         * The displays spawned for [pieces] of one sample: per band a soft quad when
         * the style is not `crisp` and a slab when the style is not `soft` and the band names a block; the edge always a slab; the rim a soft
         * quad unless `crisp`; a double-sided trail repeats every soft quad reversed.
         */
        public fun drawn(pieces: List<Piece>, style: String, bands: List<TrailBand>, edge: TrailEdge?, doubleSided: Boolean): List<Drawn> {
            val soft = style != "crisp"
            val crisp = style != "soft"
            val out = ArrayList<Drawn>()
            fun softQuad(p: Piece) {
                out += Drawn(Draw.SOFT, p, null, false)
                if (doubleSided) out += Drawn(Draw.SOFT, p, null, true)
            }
            for (p in pieces) {
                when (p.kind) {
                    PieceKind.BAND -> {
                        if (soft) softQuad(p)
                        val block = bands.getOrNull(p.index)?.block
                        if (crisp && block != null) out += Drawn(Draw.SLAB, p, block, false)
                    }
                    PieceKind.EDGE -> out += Drawn(Draw.SLAB, p, edge!!.block, false)
                    PieceKind.RIM -> if (soft) softQuad(p)
                }
            }
            return out
        }

        /** The slab axes of [s] (radial = blade direction, chord = travel across it); null when the segment is degenerate. */
        public fun slabAxes(s: Segment): SlabAxes? {
            val radial = s.v.length()
            if (radial < 1e-6) return null
            val ux = s.v * (1.0 / radial)
            val perp = s.u - ux * s.u.dot(ux)
            val chord = perp.length()
            if (chord < 1e-4) return null
            val cy = perp * (1.0 / chord)
            return SlabAxes(ux, cy, ux.cross(cy), radial, chord)
        }

        /**
         * Size of a slab at fade factor [k]: an edge ([thickness] = its width) thins radially and in
         * depth, a band slab shortens along the chord. [k] is clamped at 0.02.
         */
        public fun slabSize(a: SlabAxes, edge: Boolean, thickness: Double, k: Double): Vec3 {
            val kk = k.coerceAtLeast(0.02)
            return if (edge) Vec3(a.radial * kk, a.chord * OVERLAP, thickness * kk) else Vec3(a.radial, a.chord * OVERLAP * kk, thickness)
        }
    }
}
