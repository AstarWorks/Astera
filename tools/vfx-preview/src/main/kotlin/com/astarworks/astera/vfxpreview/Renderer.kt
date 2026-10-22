package com.astarworks.astera.vfxpreview

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

enum class ViewMode(val key: String) {
    DEFAULT("default"), TOP("top"), SIDE("side"), FRONT("front"), BEHIND("behind");

    companion object {
        fun parse(key: String): ViewMode? = entries.firstOrNull { it.key == key }
    }
}

/** Pinhole camera; screen +x = [right], screen +y (up) = [up]. */
class Camera(
    val pos: Vec3,
    val forward: Vec3,
    val right: Vec3,
    val up: Vec3,
    val focal: Double,
    val width: Int,
    val height: Int,
) {
    fun depth(p: Vec3): Double = (p - pos).dot(forward)

    /** Screen point `(x, y)` or `null` when behind the near plane. */
    fun project(p: Vec3): DoubleArray? {
        val d = p - pos
        val z = d.dot(forward)
        if (z < NEAR) return null
        return doubleArrayOf(width / 2.0 + focal * d.dot(right) / z, height / 2.0 - focal * d.dot(up) / z)
    }

    companion object {
        const val NEAR: Double = 0.05
        const val FOV_V_DEG: Double = 48.0

        /** Frames every point of [points] (plus the caster) tightly for [view]. */
        fun fit(view: ViewMode, points: List<Vec3>, width: Int, height: Int): Camera {
            val dir = when (view) { // target -> camera
                ViewMode.DEFAULT -> Vec3(-0.55, 0.42, -0.72)
                ViewMode.TOP -> Vec3(0.0, 1.0, 0.0001)
                ViewMode.SIDE -> Vec3(-1.0, 0.12, 0.0)
                ViewMode.FRONT -> Vec3(0.0, 0.2, 1.0)
                ViewMode.BEHIND -> Vec3(0.0, 0.25, -1.0)
            }.normalize()
            val forward = dir * -1.0
            val upRef = if (view == ViewMode.TOP) Vec3(0.0, 0.0, 1.0) else Vec3(0.0, 1.0, 0.0)
            val right = forward.cross(upRef).normalize()
            val up = right.cross(forward).normalize()

            val pts = points + listOf(Vec3.ZERO, Vec3(0.0, 1.9, 0.0))
            val rx = pts.map { it.dot(right) }
            val uy = pts.map { it.dot(up) }
            val fx = pts.map { it.dot(forward) }
            val cx = (rx.min() + rx.max()) / 2
            val cy = (uy.min() + uy.max()) / 2
            val cz = (fx.min() + fx.max()) / 2
            val target = right * cx + up * cy + forward * cz

            val tanV = tan(Math.toRadians(FOV_V_DEG / 2))
            val tanH = tanV * width / height
            val margin = 1.12
            var dist = 3.0
            for (i in pts.indices) {
                val x = abs(rx[i] - cx)
                val y = abs(uy[i] - cy)
                val z0 = fx[i] - cz // depth relative to target plane (positive = farther)
                dist = max(dist, x * margin / tanH - z0)
                dist = max(dist, y * margin / tanV - z0)
            }
            val pos = target - forward * dist
            return Camera(pos, forward, right, up, (height / 2.0) / tanV, width, height)
        }
    }
}

/** Software renderer (Java2D) for one tick of a [PlanReplay]. */
class Renderer(private val camera: Camera, private val label: String) {

    private class Drawable(val depth: Double, val draw: (java.awt.Graphics2D) -> Unit)

    fun render(prims: List<Prim>, tick: Int, shift: Vec3 = Vec3.ZERO): BufferedImage {
        val img = BufferedImage(camera.width, camera.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.paint = java.awt.GradientPaint(0f, 0f, Color(0x12141C), 0f, camera.height.toFloat(), Color(0x232637))
        g.fillRect(0, 0, camera.width, camera.height)

        drawGrid(g, prims)
        val items = ArrayList<Drawable>()
        figure(items, shift)
        for (p in prims) when (p) {
            is Prim.Box -> box(items, p)
            is Prim.Text -> text(items, p)
            is Prim.Item -> item(items, p)
            is Prim.Dot -> dot(items, p)
            is Prim.Quad -> quad(items, p)
        }
        items.sortByDescending { it.depth }
        for (d in items) d.draw(g)

        g.composite = AlphaComposite.SrcOver
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
        g.color = Color(0, 0, 0, 160)
        g.drawString("$label  t=$tick", 17, 41)
        g.color = Color(0xE8E8F0)
        g.drawString("$label  t=$tick", 16, 40)
        g.dispose()
        return img
    }

    // ---- floor + caster ----------------------------------------------------------

    private fun drawGrid(g: java.awt.Graphics2D, prims: List<Prim>) {
        val pts = SceneBuilder.points(prims)
        val minX = kotlin.math.floor(minOf(pts.minOfOrNull { it.x } ?: 0.0, -2.0) - 1).toInt()
        val maxX = kotlin.math.ceil(maxOf(pts.maxOfOrNull { it.x } ?: 0.0, 2.0) + 1).toInt()
        val minZ = kotlin.math.floor(minOf(pts.minOfOrNull { it.z } ?: 0.0, -2.0) - 1).toInt()
        val maxZ = kotlin.math.ceil(maxOf(pts.maxOfOrNull { it.z } ?: 0.0, 2.0) + 1).toInt()
        g.stroke = BasicStroke(1f)
        for (x in minX..maxX) {
            g.color = if (x == 0) Color(90, 110, 160, 150) else Color(70, 76, 100, 90)
            seg(g, Vec3(x.toDouble(), 0.0, minZ.toDouble()), Vec3(x.toDouble(), 0.0, maxZ.toDouble()))
        }
        for (z in minZ..maxZ) {
            g.color = if (z == 0) Color(90, 110, 160, 150) else Color(70, 76, 100, 90)
            seg(g, Vec3(minX.toDouble(), 0.0, z.toDouble()), Vec3(maxX.toDouble(), 0.0, z.toDouble()))
        }
    }

    private fun seg(g: java.awt.Graphics2D, a: Vec3, b: Vec3) {
        val (pa, pb) = clip(a, b) ?: return
        val sa = camera.project(pa) ?: return
        val sb = camera.project(pb) ?: return
        g.drawLine(sa[0].toInt(), sa[1].toInt(), sb[0].toInt(), sb[1].toInt())
    }

    private fun clip(a: Vec3, b: Vec3): Pair<Vec3, Vec3>? {
        val da = camera.depth(a) - Camera.NEAR
        val db = camera.depth(b) - Camera.NEAR
        if (da < 0 && db < 0) return null
        if (da >= 0 && db >= 0) return a to b
        val f = da / (da - db)
        val m = a + (b - a) * f
        return if (da < 0) m to b else a to m
    }

    private fun figure(out: MutableList<Drawable>, shift: Vec3) {
        val o = shift
        fun line(a: Vec3, b: Vec3, color: Color, w: Float) {
            out += Drawable(camera.depth((a + b) * 0.5)) { g ->
                g.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = color
                seg(g, a, b)
            }
        }
        val c = Color(170, 190, 175)
        val hip = o + Vec3(0.0, 0.85, 0.0)
        val neck = o + Vec3(0.0, 1.5, 0.0)
        line(o + Vec3(-0.15, 0.0, 0.0), hip, c, 4f)
        line(o + Vec3(0.15, 0.0, 0.0), hip, c, 4f)
        line(hip, neck, c, 4f)
        line(neck, o + Vec3(0.4, 1.1, 0.2), c, 3f)
        line(neck, o + Vec3(-0.4, 1.1, 0.2), c, 3f)
        val head = o + Vec3(0.0, 1.7, 0.0)
        val ring = (0..16).map { i ->
            val a = Math.PI * 2 * i / 16
            head + Vec3(Math.cos(a) * 0.2, Math.sin(a) * 0.2, 0.0)
        }
        for (i in 0 until 16) line(ring[i], ring[i + 1], c, 3f)
        line(head, head + Vec3(0.0, 0.0, 0.55), Color(255, 220, 120), 3f) // facing +Z
    }

    // ---- primitives --------------------------------------------------------------

    private val faces = listOf(
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6),
        intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6),
        intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5),
    )
    private val edges = listOf(
        0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7,
    )
    private val light = Vec3(-0.4, 0.8, -0.45).normalize()

    private fun box(out: MutableList<Drawable>, b: Prim.Box) {
        val center = b.corners.fold(Vec3.ZERO) { acc, v -> acc + v } * (1.0 / 8.0)
        // Two collapsed axes = an invisible line; skip.
        val axes = listOf(b.corners[4] - b.corners[0], b.corners[2] - b.corners[0], b.corners[1] - b.corners[0])
        if (axes.count { it.length() < 1e-3 } >= 2) return
        val base = Color(b.rgb)
        var maxDepth = camera.depth(center)

        for (f in faces) {
            val v = f.map { b.corners[it] }
            val mid = (v[0] + v[1] + v[2] + v[3]) * 0.25
            var n = (v[1] - v[0]).cross(v[3] - v[0])
            if (n.lengthSquared() < 1e-12) n = (v[2] - v[0]).cross(v[3] - v[0])
            n = n.normalize()
            val out0 = mid - center
            val oneSided = out0.length() > 1e-6
            if (oneSided && out0.dot(n) < 0) n = n * -1.0
            if (!b.translucent && oneSided && (camera.pos - mid).dot(n) <= 0) continue
            val sp = v.map { camera.project(it) }
            if (sp.any { it == null }) continue
            val depth = camera.depth(mid)
            maxDepth = max(maxDepth, depth)
            val lam = if (oneSided) max(0.0, n.dot(light)) else abs(n.dot(light))
            var shade = 0.42 + 0.58 * lam
            if (b.emissive) shade = 0.82 + 0.18 * shade
            val col = scale(base, shade)
            val path = Path2D.Double()
            sp.forEachIndexed { i, p -> if (i == 0) path.moveTo(p!![0], p[1]) else path.lineTo(p!![0], p[1]) }
            path.closePath()
            out += Drawable(depth) { g ->
                g.composite = AlphaComposite.SrcOver
                g.color = if (b.translucent) Color(col.red, col.green, col.blue, 140) else col
                g.fill(path)
                g.color = if (b.translucent) Color(255, 255, 255, 110) else scale(col, 0.7)
                g.stroke = BasicStroke(1.2f)
                g.draw(path)
            }
        }
        b.glowArgb?.let { argb ->
            val glow = Color(argb, false)
            out += Drawable(maxDepth + 0.001) { g ->
                for ((a, c) in edges) {
                    val ea = b.corners[a]
                    val eb = b.corners[c]
                    g.stroke = BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = Color(glow.red, glow.green, glow.blue, 38)
                    seg(g, ea, eb)
                    g.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = Color(glow.red, glow.green, glow.blue, 120)
                    seg(g, ea, eb)
                }
            }
        }
    }

    /** Screen-facing axes for a flat display (text / item). */
    private fun axes(rotate: (Vec3) -> Vec3, billboard: DisplayBillboard): Pair<Vec3, Vec3> = when (billboard) {
        DisplayBillboard.FIXED -> {
            var r = rotate(Vec3(1.0, 0.0, 0.0))
            val u = rotate(Vec3(0.0, 1.0, 0.0))
            if (r.dot(camera.right) < 0) r = r * -1.0 // keep glyphs legible from this camera
            r to u
        }
        DisplayBillboard.VERTICAL -> {
            val r = Vec3(camera.right.x, 0.0, camera.right.z).normalize()
            r to Vec3(0.0, 1.0, 0.0)
        }
        else -> camera.right to camera.up
    }

    private fun text(out: MutableList<Drawable>, t: Prim.Text) {
        val size = t.size
        if (abs(size.x) < 1e-3 || abs(size.y) < 1e-3) return
        val (r, u) = axes(t.rotation::rotate, t.billboard)
        val px = SceneBuilder.textPixel()
        val o = camera.project(t.center) ?: return
        val ex = camera.project(t.center + r * (size.x * px)) ?: return
        val ey = camera.project(t.center - u * (size.y * px)) ?: return
        val tx = AffineTransform(ex[0] - o[0], ex[1] - o[1], ey[0] - o[0], ey[1] - o[1], o[0], o[1])
        out += Drawable(camera.depth(t.center) - 0.0005) { g ->
            val old = g.transform
            g.transform(tx)
            val font = Fonts.glyph(t.styled.bold, t.styled.text).deriveFont(8f)
            val gv = font.createGlyphVector(g.fontRenderContext, t.styled.text)
            val b = gv.visualBounds
            val shape = AffineTransform.getTranslateInstance(-b.centerX, -b.centerY).createTransformedShape(gv.outline)
            val col = Color(t.styled.rgb)
            t.glowArgb?.let {
                val gc = Color(it, false)
                g.color = Color(gc.red, gc.green, gc.blue, 70)
                g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(shape)
            }
            g.color = col
            g.fill(shape)
            g.transform = old
        }
    }

    private fun item(out: MutableList<Drawable>, it: Prim.Item) {
        val sx = it.size.x
        val sy = it.size.y
        if (abs(sx) < 1e-3 || abs(sy) < 1e-3) return
        val (r, u) = axes(it.rotation::rotate, it.billboard)
        fun w(lx: Double, ly: Double): Vec3 = it.center + r * (lx * sx) + u * (ly * sy)
        val s2 = Math.sqrt(0.5)
        // local (along blade, across blade) -> sprite plane; the blade runs bottom-left -> top-right like a sword sprite
        fun at(along: Double, across: Double): Vec3 = w(s2 * (along + across), s2 * (along - across))
        val blade = listOf(at(-0.12, 0.07), at(0.62, 0.07), at(0.8, 0.0), at(0.62, -0.07), at(-0.12, -0.07))
        val guard = listOf(at(-0.16, 0.2), at(-0.1, 0.2), at(-0.1, -0.2), at(-0.16, -0.2))
        val grip = listOf(at(-0.48, 0.035), at(-0.16, 0.035), at(-0.16, -0.035), at(-0.48, -0.035))
        val c = camera.project(it.center) ?: return
        val depth = camera.depth(it.center)
        fun poly(ps: List<Vec3>): Path2D.Double? {
            val sp = ps.map { p -> camera.project(p) ?: return null }
            return Path2D.Double().apply {
                sp.forEachIndexed { i, p -> if (i == 0) moveTo(p[0], p[1]) else lineTo(p[0], p[1]) }
                closePath()
            }
        }
        val bp = poly(blade) ?: return
        val gp = poly(guard) ?: return
        val hp = poly(grip) ?: return
        out += Drawable(depth) { g ->
            it.glowArgb?.let { argb ->
                val gc = Color(argb, false)
                g.color = Color(gc.red, gc.green, gc.blue, 60)
                g.stroke = BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(bp)
            }
            g.color = Color(0x4B3621)
            g.fill(hp)
            g.color = scale(Color(it.rgb), 0.75)
            g.fill(gp)
            g.color = Color(it.rgb)
            g.fill(bp)
            g.color = Color(255, 255, 255, 170)
            g.stroke = BasicStroke(1.2f)
            g.draw(bp)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.color = Color(235, 235, 245)
            g.drawString(it.itemKey, (c[0] + 10).toFloat(), (c[1] + 26).toFloat())
        }
    }

    /** Display particle: an alpha-blended parallelogram (solid) or a coloured glyph. */
    private fun quad(out: MutableList<Drawable>, q: Prim.Quad) {
        if (q.width < 1e-4 || q.height < 1e-4) return
        val (r, u) = axes(q.rotation::rotate, q.billboard)
        val o = camera.project(q.center) ?: return
        val a = (q.alpha.coerceIn(0.0, 1.0) * 255).toInt()
        val depth = camera.depth(q.center)
        if (q.glyph == null) {
            val corners = listOf(
                q.center - r * (q.width / 2) - u * (q.height / 2),
                q.center + r * (q.width / 2) - u * (q.height / 2),
                q.center + r * (q.width / 2) + u * (q.height / 2),
                q.center - r * (q.width / 2) + u * (q.height / 2),
            ).map { camera.project(it) ?: return }
            val path = Path2D.Double()
            corners.forEachIndexed { i, p -> if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1]) }
            path.closePath()
            val c = Color(q.rgb)
            out += Drawable(depth) { g ->
                g.composite = AlphaComposite.SrcOver
                g.color = Color(c.red, c.green, c.blue, a)
                g.fill(path)
            }
        } else {
            val ex = camera.project(q.center + r * q.width) ?: return
            val ey = camera.project(q.center - u * q.height) ?: return
            val tx = AffineTransform(ex[0] - o[0], ex[1] - o[1], ey[0] - o[0], ey[1] - o[1], o[0], o[1])
            out += Drawable(depth) { g ->
                val old = g.transform
                g.transform(tx)
                val gv = Fonts.glyph(false, q.glyph).deriveFont(1f).createGlyphVector(g.fontRenderContext, q.glyph)
                val b = gv.visualBounds
                val shape = AffineTransform.getTranslateInstance(-b.centerX, -b.centerY).createTransformedShape(gv.outline)
                val c = Color(q.rgb)
                g.composite = AlphaComposite.SrcOver
                g.color = Color(c.red, c.green, c.blue, a)
                g.fill(shape)
                g.transform = old
            }
        }
    }

    private fun dot(out: MutableList<Drawable>, d: Prim.Dot) {
        val s = camera.project(d.position) ?: return
        val z = camera.depth(d.position)
        val fade = 1.0 - d.age / (PlanReplay.PARTICLE_LIFETIME_TICKS + 0.0)
        val r = max(2.5, 0.07 * camera.focal / z)
        out += Drawable(z) { g ->
            g.color = Color(255, 190, 60, (70 * fade).toInt().coerceIn(0, 255))
            g.fill(Ellipse2D.Double(s[0] - r * 2.2, s[1] - r * 2.2, r * 4.4, r * 4.4))
            g.color = Color(255, 240, 160, (255 * fade).toInt().coerceIn(0, 255))
            g.fill(Ellipse2D.Double(s[0] - r, s[1] - r, r * 2, r * 2))
        }
    }

    private fun scale(c: Color, f: Double): Color =
        Color((c.red * f).toInt().coerceIn(0, 255), (c.green * f).toInt().coerceIn(0, 255), (c.blue * f).toInt().coerceIn(0, 255))
}

/** Font lookup with CJK coverage (falls back to the logical Dialog font). */
object Fonts {
    private val family: String by lazy {
        val names = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()
        val probe = "斬撃"
        names.filter { it.contains("CJK", ignoreCase = true) && it.contains("Sans") }
            .plus(names.filter { it.contains("CJK", ignoreCase = true) })
            .firstOrNull { Font(it, Font.PLAIN, 12).canDisplayUpTo(probe) == -1 }
            ?: Font.DIALOG
    }

    private val symbolFamilies = listOf("DejaVu Sans", "Noto Sans Symbols 2", "Noto Sans Symbols", Font.DIALOG)

    /** Picks the CJK family, or a symbol-capable one when it cannot draw [text] (e.g. ✦ ★ ☸). */
    fun glyph(bold: Boolean, text: String = ""): Font {
        val style = if (bold) Font.BOLD else Font.PLAIN
        return (listOf(family) + symbolFamilies)
            .map { Font(it, style, 12) }
            .firstOrNull { it.canDisplayUpTo(text) == -1 }
            ?: Font(family, style, 12)
    }
}
