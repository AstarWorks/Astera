package com.astarworks.astera.itemart

import java.awt.Color
import kotlin.math.*
import com.astarworks.astera.itemart.SpriteGraph.Image

/** CPU ports of AsteraStudio's PixelComposer node vocabulary. See LICENSE-PIXEL-COMPOSER.txt. */
internal object GraphNodes {
    data class Context(val size: Int, val frame: Int, val frames: Int) {
        val t = if (frames <= 1) 0.0 else frame.toDouble() / (frames - 1)
        val seconds = frame / 20.0
    }
    fun importImage(source: java.awt.image.BufferedImage, size: Int): Image = raster(size) { x, y ->
        val c = source.getRGB((x / size * source.width).toInt().coerceIn(0, source.width - 1), (y / size * source.height).toInt().coerceIn(0, source.height - 1))
        floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f, (c ushr 24) / 255f)
    }
    private fun Map<String, Any>.n(key: String) = (getValue(key) as Number).toDouble()
    private fun Map<String, Any>.s(key: String) = getValue(key) as String
    private fun Map<String, Any>.v(key: String) = (getValue(key) as List<*>).map { (it as Number).toDouble() }
    private fun Map<String, Any>.b(key: String) = getValue(key) as Boolean
    fun color(hex: String): FloatArray {
        val h = hex.removePrefix("#")
        return floatArrayOf(h.substring(0, 2).toInt(16) / 255f, h.substring(2, 4).toInt(16) / 255f, h.substring(4, 6).toInt(16) / 255f, if (h.length == 8) h.substring(6, 8).toInt(16) / 255f else 1f)
    }
    private fun raster(size: Int, f: (Double, Double) -> FloatArray): Image {
        val out = Image(size)
        for (y in 0 until size) for (x in 0 until size) f(x + 0.5, y + 0.5).copyInto(out.rgba, (y * size + x) * 4)
        return out
    }
    private fun sample(src: Image, x: Double, y: Double, clamp: Boolean = false): FloatArray {
        var ix = floor(x).toInt(); var iy = floor(y).toInt()
        if (clamp) { ix = ix.coerceIn(0, src.size - 1); iy = iy.coerceIn(0, src.size - 1) }
        return FloatArray(4) { src.at(ix, iy, it) }
    }
    private fun remap(src: Image, f: (Double, Double) -> Pair<Double, Double>) = raster(src.size) { x, y -> val (sx, sy) = f(x, y); sample(src, sx, sy) }
    private fun alpha(src: Image, factor: Double) = Image(src.size, src.rgba.copyOf().also { a -> for (i in 3 until a.size step 4) a[i] *= factor.toFloat() })
    private fun transform(src: Image, sx: Double = 1.0, sy: Double = sx, angle: Double = 0.0, ox: Double = src.size / 2.0, oy: Double = src.size / 2.0, dx: Double = 0.0, dy: Double = 0.0): Image {
        if (abs(sx) < 1e-9 || abs(sy) < 1e-9) return Image(src.size)
        val a = angle * PI / 180; val co = cos(a); val si = sin(a)
        return remap(src) { x, y -> val u = x - ox - dx; val v = y - oy - dy; (ox + (co * u + si * v) / sx) to (oy + (-si * u + co * v) / sy) }
    }
    /** a is foreground, b background. Straight-alpha W3C blend equations. */
    fun blend(a: Image, b: Image, mode: String): Image {
        val out = Image(a.size)
        for (i in a.rgba.indices step 4) {
            val aa = a.rgba[i + 3].coerceIn(0f, 1f); val ba = b.rgba[i + 3].coerceIn(0f, 1f)
            val oa = if (mode == "add") min(1f, aa + ba) else aa + ba * (1 - aa)
            out.rgba[i + 3] = oa
            for (c in 0..2) {
                val av = a.rgba[i + c]; val bv = b.rgba[i + c]
                val mixed = when (mode) { "multiply" -> av * bv; "screen" -> 1 - (1 - av) * (1 - bv); "max" -> max(av, bv); else -> av }
                out.rgba[i + c] = if (oa <= 0) 0f else if (mode == "add") (av * aa + bv * ba) / oa else
                    ((1 - ba) * av * aa + (1 - aa) * bv * ba + aa * ba * mixed) / oa
            }
        }
        return out
    }
    private fun lum(src: Image, x: Int, y: Int): Double = (src.at(x.coerceIn(0, src.size - 1), y.coerceIn(0, src.size - 1), 0) + src.at(x.coerceIn(0, src.size - 1), y.coerceIn(0, src.size - 1), 1) + src.at(x.coerceIn(0, src.size - 1), y.coerceIn(0, src.size - 1), 2)) / 3.0
    private fun blur(src: Image, radius: Int): Image {
        if (radius == 0) return src
        val sigma = max(0.5, radius / 3.0)
        val weights = (-radius..radius).map { exp(-it * it / (2 * sigma * sigma)) }.toDoubleArray()
        val total = weights.sum(); for (i in weights.indices) weights[i] /= total
        var cur = src
        for (horizontal in listOf(true, false)) {
            val next = Image(src.size)
            for (y in 0 until src.size) for (x in 0 until src.size) {
                val acc = DoubleArray(4)
                for (k in -radius..radius) {
                    val sx = if (horizontal) x + k else x; val sy = if (horizontal) y else y + k
                    val a = cur.at(sx, sy, 3).toDouble(); val w = weights[k + radius]
                    for (c in 0..2) acc[c] += cur.at(sx, sy, c) * a * w
                    acc[3] += a * w
                }
                val i = (y * src.size + x) * 4
                for (c in 0..2) next.rgba[i + c] = if (acc[3] == 0.0) 0f else (acc[c] / acc[3]).toFloat()
                next.rgba[i + 3] = acc[3].toFloat()
            }
            cur = next
        }
        return cur
    }
    private fun morph(src: Image, radius: Int, erode: Boolean = false): Image = raster(src.size) { x, y ->
        var best = sample(src, x, y)
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy > radius * radius) continue
            val p = sample(src, x + dx, y + dy)
            if (if (erode) p[3] < best[3] else p[3] > best[3]) best = p
        }
        best
    }
    fun noise(x: Double, y: Double, seed: Int, perlin: Boolean = false): Double {
        fun hash(ix: Int, iy: Int): Double {
            var h = ix * 374761393 + iy * 668265263 + seed * 69069
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)).toLong() and 0xffffffffL) / 4294967295.0
        }
        val ix = floor(x).toInt(); val iy = floor(y).toInt(); val fx = x - ix; val fy = y - iy
        val u = fx * fx * fx * (fx * (fx * 6 - 15) + 10); val v = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
        fun lattice(dx: Int, dy: Int): Double {
            val h = hash(ix + dx, iy + dy)
            return if (perlin) cos(h * 2 * PI) * (fx - dx) + sin(h * 2 * PI) * (fy - dy) else h
        }
        val a = lattice(0, 0) * (1 - u) + lattice(1, 0) * u
        val b = lattice(0, 1) * (1 - u) + lattice(1, 1) * u
        return if (perlin) ((a * (1 - v) + b * v) * 0.7 + 0.5).coerceIn(0.0, 1.0) else a * (1 - v) + b * v
    }
    private fun ease(t: Double, kind: String): Double {
        val u = t.coerceIn(0.0, 1.0)
        return when (kind) {
            "ease_in" -> u * u; "ease_out" -> 1 - (1 - u).pow(2); "ease_in_out" -> if (u < 0.5) 2 * u * u else 1 - (-2 * u + 2).pow(2) / 2
            "back_out" -> 1 + 2.70158 * (u - 1).pow(3) + 1.70158 * (u - 1).pow(2)
            "elastic_out" -> if (u == 0.0 || u == 1.0) u else 2.0.pow(-10 * u) * sin((u * 10 - 0.75) * 2 * PI / 3) + 1
            "bounce_out" -> { val d = 2.75; when { u < 1 / d -> 7.5625 * u * u; u < 2 / d -> 7.5625 * (u - 1.5 / d).pow(2) + 0.75; u < 2.5 / d -> 7.5625 * (u - 2.25 / d).pow(2) + 0.9375; else -> 7.5625 * (u - 2.625 / d).pow(2) + 0.984375 } }
            "sin" -> sin(u * PI); "square" -> if (u < 0.5) 0.0 else 1.0; else -> u
        }
    }
    private fun stops(raw: Any) = (raw as List<*>).map { it as Map<*, *> }
    private fun curve(raw: Any, t: Double): Double {
        val s = stops(raw)
        fun v(i: Int) = (s[i]["v"] as Number).toDouble()
        if (t <= (s.first()["t"] as Number).toDouble()) return v(0)
        for (i in 0 until s.lastIndex) {
            val a = (s[i]["t"] as Number).toDouble(); val b = (s[i + 1]["t"] as Number).toDouble()
            if (t <= b) return v(i) + (v(i + 1) - v(i)) * (t - a) / (b - a)
        }
        return v(s.lastIndex)
    }
    private fun gradient(raw: Any, t: Double): FloatArray {
        val s = stops(raw)
        if (t <= (s.first()["t"] as Number).toDouble()) return color(s.first()["color"] as String)
        for (i in 0 until s.lastIndex) {
            val a = (s[i]["t"] as Number).toDouble(); val b = (s[i + 1]["t"] as Number).toDouble()
            if (t <= b) { val c = color(s[i]["color"] as String); val d = color(s[i + 1]["color"] as String); return FloatArray(4) { (c[it] + (d[it] - c[it]) * (t - a) / (b - a)).toFloat() } }
        }
        return color(s.last()["color"] as String)
    }
    fun compute(type: String, p: Map<String, Any>, inputs: Map<String, Any>, ctx: Context, previous: () -> Image, past: (String, Int) -> Image): Any {
        val size = ctx.size
        val src = inputs["image"] as? Image
        fun image() = requireNotNull(src) { "$type requires image" }
        fun count(key: String, min: Int = 1, max: Int = 1024): Int { val v = p.n(key); require(v % 1.0 == 0.0 && v in min.toDouble()..max.toDouble()) { "$type.$key must be integer $min..$max" }; return v.toInt() }
        when {
            type == "output" -> return image()
            type == "anim.time" -> return ctx.t
            type == "values.number" -> return p.n("value")
            type == "values.vec2" -> return listOf(p.n("x"), p.n("y"))
            type.startsWith("values.") -> return p.getValue("value")
            type == "anim.curve" -> return curve(p.getValue("keyframes"), if (p.s("mode") == "pingpong") 1 - abs(2 * ctx.t - 1) else ctx.t)
            type == "anim.wiggler" -> return p.n("center") + p.n("amplitude") * (noise(ctx.seconds * p.n("frequency"), 0.0, p.n("seed").toInt()) * 2 - 1)
            type == "particle.effector" -> return p
            type == "shape.field" -> return field(p, ctx)
            type.startsWith("shape.") -> return shape(type, p, size)
            type.startsWith("noise.") -> return raster(size) { x, y ->
                var sum = 0.0; var weight = 0.0
                for (o in 0 until count("octaves", 1, 8)) { val a = 0.5.pow(o); sum += noise(x / size * p.n("scale") * 2.0.pow(o), y / size * p.n("scale") * 2.0.pow(o), p.n("seed").toInt(), type == "noise.perlin") * a; weight += a }
                val v = (sum / weight).toFloat(); floatArrayOf(v, v, v, 1f)
            }
            type == "generate.gradient_radial" -> return raster(size) { x, y ->
                val span = p.n("outer_radius") - p.n("inner_radius"); require(span > 0) { "outer_radius must exceed inner_radius" }
                val t = ((hypot(x - p.n("cx"), y - p.n("cy")) - p.n("inner_radius")) / span).coerceIn(0.0, 1.0)
                val a = color(p.s("color_inner")); val b = color(p.s("color_outer")); FloatArray(4) { (a[it] + (b[it] - a[it]) * t).toFloat() }
            }
            type == "generate.scatter" || type == "generate.fx_scatter" -> {
                val rng = kotlin.random.Random(p.n("seed").toInt()); var out = Image(size)
                val wanted = count("count", 0, 4096)
                var placed = 0; var attempts = 0
                while (placed < wanted && attempts++ < wanted * 30) {
                    val x = rng.nextDouble() * size; val y = rng.nextDouble() * size; val r = rng.nextDouble()
                    val mask = inputs["mask"] as? Image
                    if (mask != null && mask.at(x.toInt(), y.toInt(), 3) <= 0f) continue
                    placed++
                    val stamp = if (type == "generate.fx_scatter") {
                        val scale = p.n("scale_min") + (p.n("scale_max") - p.n("scale_min")) * r
                        alpha(transform(inputs.getValue("pattern") as Image, sx = scale, angle = (rng.nextDouble() * 2 - 1) * p.n("rotate_max_deg"), dx = x - size / 2.0, dy = y - size / 2.0), 1 - rng.nextDouble() * p.n("alpha_jitter"))
                    } else particleShape(size, x, y, p.n("size_min") + (p.n("size_max") - p.n("size_min")) * r, p.s("shape"), color(p.s("color")))
                    out = blend(stamp, out, "over")
                }
                return out
            }
            type == "particle.emitter" -> return particles(p, inputs, ctx)
            type.startsWith("composite.") -> return blend(inputs.getValue("a") as Image, inputs.getValue("b") as Image, type.substringAfter('.'))
            type == "xform.translate" -> return transform(image(), dx = p.n("dx"), dy = p.n("dy"))
            type == "xform.rotate" -> return transform(image(), angle = p.n("angle_deg"), ox = p.n("ox"), oy = p.n("oy"))
            type == "xform.scale" -> return transform(image(), sx = p.n("sx"), sy = p.n("sy"), ox = p.n("ox"), oy = p.n("oy"))
            type == "xform.mirror" -> return remap(image()) { x, y -> (if (p.s("axis") in setOf("x", "both")) size - x else x) to (if (p.s("axis") in setOf("y", "both")) size - y else y) }
            type == "anim.rotate" -> return transform(image(), angle = p.n("speed_deg_per_sec") * ctx.seconds, ox = p.v("origin")[0], oy = p.v("origin")[1])
            type == "anim.scale_pulse" -> {
                val period = p.n("period_seconds"); require(period > 0) { "period_seconds must be positive" }
                val phase = ctx.seconds / period % 1.0
                val u = if (p.s("easing") in setOf("sin", "square")) phase else 1 - abs(2 * phase - 1)
                return transform(image(), sx = p.n("min") + (p.n("max") - p.n("min")) * ease(u, p.s("easing")))
            }
            type == "anim.scale_ramp" -> {
                val duration = p.n("duration_frames"); require(duration > 0) { "duration_frames must be positive" }
                val relative = ctx.frame - p.n("start_frame"); var t = max(0.0, relative / duration); var a = 1.0
                if (t >= 1) when (p.s("after")) { "loop" -> t %= 1; "fade" -> { val rest = ctx.frames - p.n("start_frame") - duration; a = if (rest <= 0) 0.0 else (1 - (relative - duration) / rest).coerceIn(0.0, 1.0) } }
                return alpha(transform(image(), sx = p.n("from") + (p.n("to") - p.n("from")) * ease(t, p.s("easing"))), a)
            }
            type == "anim.stagger" -> {
                var out = Image(size)
                for (i in (count("count", 1, 64) - 1) downTo 0) {
                    val f = ctx.frame - i * count("delay_frames", 1, 256)
                    if (f >= 0) out = blend(alpha(past("image", f), p.n("decay_alpha").pow(i)), out, "over")
                }
                return out
            }
            type in setOf("filter.trail", "filter.feedback") -> {
                require(p.n("decay") in 0.0..1.0) { "decay must be 0..1" }
                var out = previous()
                if (type == "filter.feedback") out = transform(out, sx = p.n("feedback_scale"), angle = p.n("feedback_rotate_deg"), dx = p.n("offset_x"), dy = p.n("offset_y"))
                out = blend(image(), alpha(out, p.n("decay")), p.s("mode"))
                return out
            }
            type == "xform.polar" || type in setOf("xform.repeat_radial", "xform.polar_repeat", "xform.kaleidoscope") -> {
                val origin = p.v("origin"); val ox = origin[0]; val oy = origin[1]
                val maxR = hypot(max(ox, size - ox), max(oy, size - oy))
                return remap(image()) { x, y ->
                    if (type == "xform.polar") {
                        val twist = p.n("twist_deg") * PI / 180
                        if (p.s("mode") == "to_polar") { val a = x / size * 2 * PI + twist; val r = y / size * maxR; (ox + cos(a) * r) to (oy + sin(a) * r) }
                        else { val a = atan2(y - oy, x - ox) + twist; ((a / (2 * PI) % 1 + 1) % 1 * size) to (hypot(x - ox, y - oy) / maxR * size) }
                    } else {
                        val sector = 2 * PI / count("count", 1, 128); val r = hypot(x - ox, y - oy)
                        var a = ((atan2(y - oy, x - ox) - p.n("rotation_deg") * PI / 180) % sector + sector) % sector
                        if (type == "xform.kaleidoscope") a = min(a, sector - a)
                        (ox + cos(a) * r) to (oy + sin(a) * r)
                    }
                }
            }
            type == "filter.blur" -> return blur(image(), count("radius", 0, 64))
            type == "filter.dilate" -> return morph(image(), count("radius", 0, 32))
            type == "filter.bloom" -> {
                val tint = color(p.s("color")); val bright = Image(size, image().rgba.copyOf())
                for (i in bright.rgba.indices step 4) if (bright.rgba[i] * 0.2126 + bright.rgba[i + 1] * 0.7152 + bright.rgba[i + 2] * 0.0722 < p.n("threshold")) bright.rgba[i + 3] = 0f
                val glow = blur(bright, count("radius", 0, 64))
                for (i in glow.rgba.indices step 4) {
                    val hsb = Color.RGBtoHSB((glow.rgba[i].coerceIn(0f, 1f) * 255).toInt(), (glow.rgba[i + 1].coerceIn(0f, 1f) * 255).toInt(), (glow.rgba[i + 2].coerceIn(0f, 1f) * 255).toInt(), null)
                    val rgb = Color.HSBtoRGB(hsb[0], (hsb[1] * p.n("saturation")).toFloat().coerceIn(0f, 1f), hsb[2])
                    for (c in 0..2) glow.rgba[i + c] = ((rgb shr (16 - 8 * c)) and 255) / 255f * tint[c]
                }
                return blend(image(), alpha(glow, p.n("intensity")), "add")
            }
            type == "filter.palette" -> {
                val raster = PixelConverter.Raster.of(image().buffered())
                if (raster.px.none { it ushr 24 != 0 }) return image()
                val colors = PixelConverter.palette(raster, count("colors", 2, 64)).map { c -> floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f, 1f) }
                return raster(size) { x, y -> val c = sample(image(), x, y); val best = colors.minBy { (0..2).sumOf { k -> (it[k] - c[k]).toDouble().pow(2) } }; floatArrayOf(best[0], best[1], best[2], c[3] * best[3]) }
            }
            type == "filter.outline" -> {
                val radius = count("thickness", 1, 32); val dilated = morph(image(), radius); val eroded = morph(image(), radius, true); val ink = color(p.s("color"))
                return raster(size) { x, y -> val c = sample(image(), x, y); val outer = sample(dilated, x, y)[3] - c[3]; val inner = c[3] - sample(eroded, x, y)[3]; val a = when (p.s("mode")) { "inside" -> inner; else -> outer }; if (a > 0) ink.copyOf().also { it[3] *= a } else if (p.s("mode") == "replace") FloatArray(4) else c }
            }
            type == "filter.dither" && p.s("pattern") == "floyd" -> {
                val levels = count("palette_size", 2, 16) - 1
                val out = Image(size, image().rgba.copyOf())
                for (y in 0 until size) for (x in 0 until size) for (c in 0..2) {
                    val i = (y * size + x) * 4 + c
                    val old = out.rgba[i]; val next = (old * levels).roundToInt().toFloat() / levels
                    val error = old - next; out.rgba[i] = next.coerceIn(0f, 1f)
                    for ((dx, dy, weight) in listOf(Triple(1, 0, 7f), Triple(-1, 1, 3f), Triple(0, 1, 5f), Triple(1, 1, 1f))) {
                        if (x + dx in 0 until size && y + dy in 0 until size) out.rgba[((y + dy) * size + x + dx) * 4 + c] += error * weight / 16
                    }
                }
                return out
            }
            type == "filter.dither" -> return raster(size) { x, y ->
                val c = sample(image(), x, y); val levels = count("palette_size", 2, 256) - 1
                val b = when (p.s("pattern")) { "bayer2" -> { val a = intArrayOf(0, 2, 3, 1); (a[(y.toInt() and 1) * 2 + (x.toInt() and 1)] + 0.5) / 4 }; "bayer8" -> { fun b2(x: Int, y: Int) = intArrayOf(0, 2, 3, 1)[(y and 1) * 2 + (x and 1)]
                        (16 * b2(x.toInt(), y.toInt()) + 4 * b2(x.toInt() / 2, y.toInt() / 2) + b2(x.toInt() / 4, y.toInt() / 4) + 0.5) / 64 }; else -> FieldExpression.bayer(x.toInt(), y.toInt()) }
                for (k in 0..2) c[k] = (floor(c[k] * levels + b) / levels).toFloat(); c
            }
            type == "filter.directional_warp" -> {
                val map = inputs["map"] as? Image; val a = p.n("angle_deg") * PI / 180
                return raster(size) { x, y -> val d = ((map?.let { lum(it, x.toInt(), y.toInt()) } ?: 0.5) - 0.5) * 2 * p.n("intensity"); sample(image(), x - cos(a) * d, y - sin(a) * d, true) }
            }
            type == "filter.slope_blur" -> {
                val slope = inputs["slope"] as? Image ?: return image(); val samples = count("samples", 1, 32)
                return raster(size) { x, y ->
                    var gx = lum(slope, x.toInt() + 1, y.toInt()) - lum(slope, x.toInt() - 1, y.toInt()); var gy = lum(slope, x.toInt(), y.toInt() + 1) - lum(slope, x.toInt(), y.toInt() - 1)
                    val mag = hypot(gx, gy); if (mag > 1e-6) { gx /= mag; gy /= mag }
                    val acc = FloatArray(4)
                    repeat(samples) { k -> val s = sample(image(), x + gx * p.n("intensity") / samples * k, y + gy * p.n("intensity") / samples * k, true); for (c in 0..3) acc[c] += s[c] / samples }
                    acc
                }
            }
            type == "filter.seamless" -> {
                val width = p.n("blend"); require(width in 0.0..0.5) { "blend must be 0..0.5" }
                return raster(size) { x, y ->
                    val a = sample(image(), x, y); val b = sample(image(), (x + size / 2) % size, (y + size / 2) % size)
                    val edge = min(min(x, size - x), min(y, size - y)) / size
                    val w = if (width == 0.0) 0.0 else 0.5 * (1 - edge / width).coerceIn(0.0, 1.0)
                    FloatArray(4) { (a[it] * (1 - w) + b[it] * w).toFloat() }
                }
            }
        }
        error("unimplemented node $type")
    }
    private fun shape(type: String, p: Map<String, Any>, size: Int): Image {
        val ink = color(p.s("color"))
        val cx = (p["cx"] as? Number)?.toDouble() ?: size / 2.0; val cy = (p["cy"] as? Number)?.toDouble() ?: size / 2.0
        val radius = (p["radius"] as? Number)?.toDouble() ?: 1.0
        require(radius >= 0) { "$type.radius must be nonnegative" }
        val thickness = (p["thickness"] as? Number)?.toDouble() ?: 0.0
        require(thickness >= 0) { "$type.thickness must be nonnegative" }
        var points = emptyList<Pair<Double, Double>>()
        if (type in setOf("shape.polygon", "shape.star")) {
            val n = p.n(if (type == "shape.star") "points" else "sides")
            require(n % 1 == 0.0 && n in 3.0..128.0) { "$type needs 3..128 integer sides/points" }
            val count = n.toInt(); val rotation = p.n("rotation_deg") * PI / 180 - PI / 2
            val step = if (type == "shape.star") p.n("step") else 1.0
            require(step % 1 == 0.0 && step >= 1 && step < count) { "star.step must be 1..points-1" }
            points = if (type == "shape.star" && step == 1.0) (0 until count * 2).map { i ->
                val a = rotation + PI * i / count; val r = if (i % 2 == 0) radius else p.n("inner_radius")
                (cx + cos(a) * r) to (cy + sin(a) * r)
            } else (0 until count).map { i -> val a = rotation + 2 * PI * i * step / count; (cx + cos(a) * radius) to (cy + sin(a) * radius) }
        }
        return raster(size) { x, y ->
            val d = when (type) {
                "shape.circle" -> hypot(x - cx, y - cy) - radius
                "shape.ring" -> abs(hypot(x - cx, y - cy) - radius) - thickness / 2
                "shape.line" -> FieldExpression.segment(x, y, p.n("x0"), p.n("y0"), p.n("x1"), p.n("y1")) - thickness / 2
                "shape.arc" -> {
                    val start = p.n("start_deg") * PI / 180; val span = (p.n("end_deg") - p.n("start_deg")) * PI / 180
                    require(span > 0 && span <= 2 * PI) { "arc span must be (0,360]" }
                    val a = ((atan2(y - cy, x - cx) - start) % (2 * PI) + 2 * PI) % (2 * PI)
                    if (a > span) 1e6 else abs(hypot(x - cx, y - cy) - radius) - thickness / 2 * (if (p.n("taper") == 0.0) 1.0 else sin(PI * a / span).coerceAtLeast(0.0).pow(p.n("taper")))
                }
                else -> {
                    var inside = false; var nearest = Double.POSITIVE_INFINITY
                    for (i in points.indices) {
                        val (ax, ay) = points[i]; val (bx, by) = points[(i + 1) % points.size]
                        nearest = min(nearest, FieldExpression.segment(x, y, ax, ay, bx, by))
                        if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
                    }
                    if (thickness > 0) nearest - thickness / 2 else if (inside) -nearest else nearest
                }
            }
            ink.copyOf().also { it[3] *= (0.5 - d).coerceIn(0.0, 1.0).toFloat() }
        }
    }

    private fun field(p: Map<String, Any>, ctx: Context): Image {
        val symbols = FieldExpression.BUILT_INS.toMutableList()
        val expressions = (p.getValue("variables") as Map<*, *>).map { (key, value) ->
            val compiled = FieldExpression.compile(value.toString(), symbols)
            symbols += key.toString(); compiled
        }
        val expression = FieldExpression.compile(p.s("expression"), symbols)
        val values = DoubleArray(symbols.size)
        values[2] = ctx.t; values[3] = ctx.frame.toDouble(); values[4] = ctx.frames.toDouble(); values[5] = ctx.size.toDouble(); values[8] = PI
        val ramp = ramp(p.s("color"))
        fun eval(x: Double, y: Double, px: Int, py: Int): Double {
            values[0] = x; values[1] = y; values[6] = px.toDouble(); values[7] = py.toDouble()
            expressions.forEachIndexed { index, f -> values[FieldExpression.BUILT_INS.size + index] = f(values) }
            val result = expression(values)
            require(result.isFinite()) { "non-finite shape.field at frame ${ctx.frame}, ($x,$y)" }
            return result
        }
        val out = Image(ctx.size); val k = 32.0 / ctx.size
        val smooth = p.b("smooth") && ctx.size >= 128
        for (y in 0 until ctx.size) for (x in 0 until ctx.size) {
            var sum = 0.0; var covered = 0
            if (smooth) {
                for (oy in listOf(0.25, 0.75)) for (ox in listOf(0.25, 0.75)) {
                    val v = eval((x + ox) * k, (y + oy) * k, x, y)
                    if (v >= 0.1) { sum += v; covered++ }
                }
            } else { val v = eval((x + 0.5) * k, (y + 0.5) * k, x, y); if (v >= 0.1) { sum = v; covered = 1 } }
            if (covered == 0) continue
            val intensity = sum / covered
            val c = if (p.b("ramp")) ramp[when { intensity >= 0.8 -> 3; intensity >= 0.55 -> 2; intensity >= 0.3 -> 1; else -> 0 }] else floatArrayOf(intensity.toFloat(), intensity.toFloat(), intensity.toFloat(), 1f)
            val offset = (y * ctx.size + x) * 4
            c.copyInto(out.rgba, offset)
            out.rgba[offset + 3] = (if (smooth) 255 * covered / 4 else 255) / 255f
        }
        for (raw in p.getValue("dots") as List<*>) {
            val dot = raw as Map<*, *>
            fun e(key: String, default: String = "0") = FieldExpression.compile(dot[key]?.toString() ?: default, FieldExpression.BUILT_INS)(values)
            if (e("when", "1") == 0.0) continue
            val x = floor(e("x") / k).toInt(); val y = floor(e("y") / k).toInt(); val v = e("value")
            if (x !in 0 until ctx.size || y !in 0 until ctx.size || v < 0.1 || out.at(x, y, 3) != 0f) continue
            ramp[when { v >= 0.8 -> 3; v >= 0.55 -> 2; v >= 0.3 -> 1; else -> 0 }].copyInto(out.rgba, (y * ctx.size + x) * 4)
        }
        return out
    }
    private fun ramp(hex: String): List<FloatArray> {
        val base = Color(hex.substring(1, 7).toInt(16))
        val hsb = Color.RGBtoHSB(base.red, base.green, base.blue, null)
        fun rgba(c: Color) = floatArrayOf(c.red / 255f, c.green / 255f, c.blue / 255f, 1f)
        fun toward(f: Double) = rgba(Color((base.red + (255 - base.red) * f).toInt(), (base.green + (255 - base.green) * f).toInt(), (base.blue + (255 - base.blue) * f).toInt()))
        return listOf(rgba(Color.getHSBColor(hsb[0], min(1f, hsb[1] * 1.1f + 0.05f), hsb[2] * 0.5f)), rgba(base), toward(0.45), toward(0.86))
    }
    private fun particleShape(size: Int, cx: Double, cy: Double, radius: Double, type: String, ink: FloatArray): Image = raster(size) { x, y ->
        val dx = abs(x - cx); val dy = abs(y - cy)
        val d = when (type) { "square" -> max(dx, dy) * 2; "diamond" -> dx + dy; "star" -> {
            val a = atan2(y - cy, x - cx) + PI / 2
            val local = ((a % (2 * PI / 5) + 2 * PI / 5) % (2 * PI / 5)) / (2 * PI / 5)
            hypot(dx, dy) / (0.45 + 0.55 * abs(2 * local - 1))
        }; else -> hypot(dx, dy) }
        ink.copyOf().also { it[3] *= (radius - d + 0.5).coerceIn(0.0, 1.0).toFloat() }
    }
    private fun particles(p: Map<String, Any>, inputs: Map<String, Any>, ctx: Context): Image {
        val rng = kotlin.random.Random(p.n("seed").toInt())
        val count = p.n("count").toInt(); require(count in 0..4096) { "particle count must be 0..4096" }
        val life = p.n("lifetime_frames"); require(life >= 1) { "lifetime_frames must be >=1" }
        val origin = p.v("spawn_origin"); var out = Image(ctx.size)
        val eff = inputs["effector"]?.let { SpriteGraph.map(it, "effector").mapValues { (_, v) -> requireNotNull(v) } }
        for (index in 0 until count) {
            val spawn = if (p.s("spawn_mode") == "burst") 0.0 else index.toDouble() / max(1, count) * ctx.frames
            val angle = rng.nextDouble() * 2 * PI; val radius = sqrt(rng.nextDouble()) * p.n("spawn_radius")
            val dir = when (p.s("velocity_direction")) {
                "radial" -> angle
                "random" -> rng.nextDouble() * 2 * PI
                else -> p.n("velocity_angle_deg") * PI / 180 + (rng.nextDouble() - 0.5) * 0.2
            }
            val speed = p.n("velocity_min") + rng.nextDouble() * (p.n("velocity_max") - p.n("velocity_min"))
            val age = ctx.frame - spawn
            if (age < 0 || age >= life) continue
            var x = origin[0] + cos(angle) * radius; var y = origin[1] + sin(angle) * radius
            var vx = cos(dir) * speed; var vy = sin(dir) * speed
            // Replay from spawn: same result for random-access previews and sequential frame generation.
            repeat(floor(age).toInt()) { step ->
                if (eff != null) {
                    val strength = eff.n("strength"); val mode = eff.s("mode")
                    if (mode == "turbulence") {
                        val f = eff.n("noise_scale") / ctx.size; val z = (spawn + step) * eff.n("evolution_speed") * 0.2
                        var fx = 0.0; var fy = 0.0
                        val octaves = eff.n("octaves").toInt(); require(octaves in 1..6) { "effector octaves must be 1..6" }
                        for (o in 0 until octaves) {
                            val freq = f * 2.0.pow(o); val amp = 0.5.pow(o); val seed = eff.n("noise_seed").toInt()
                            fx += (noise(x * freq + z, y * freq + 0.01, seed, true) - noise(x * freq + z, y * freq - 0.01, seed, true)) / 0.02 * amp
                            fy -= (noise(x * freq + z + 0.01, y * freq, seed, true) - noise(x * freq + z - 0.01, y * freq, seed, true)) / 0.02 * amp
                        }
                        vx += fx * strength; vy += fy * strength
                    } else if (mode == "wind") vx += strength else {
                        val e = eff.v("origin"); val dx = e[0] - x; val dy = e[1] - y; val d = hypot(dx, dy); val r = eff.n("radius")
                        require(r > 0) { "effector radius must be positive" }
                        if (d > 1e-6 && d <= r) {
                            val falloff = when (eff.s("falloff")) { "none" -> 1.0; "inverse_square" -> 1 / (1 + (d / r).pow(2)); else -> 1 - d / r }
                            val mag = strength * falloff / d
                            when (mode) { "repel" -> { vx -= dx * mag; vy -= dy * mag }; "vortex" -> { vx -= dy * mag; vy += dx * mag }; else -> { vx += dx * mag; vy += dy * mag } }
                        }
                    }
                }
                x += vx; y += vy
            }
            x += vx * (age % 1); y += vy * (age % 1)
            val t = age / life; val r = p.n("size_start") + (p.n("size_end") - p.n("size_start")) * t
            val a = curve(p.getValue("alpha_over_life"), t).coerceIn(0.0, 1.0)
            val sprite = inputs["sprite"] as? Image
            val stamp = if (sprite == null) particleShape(ctx.size, x, y, r, p.s("shape"), gradient(p.getValue("gradient"), t)) else {
                val cols = p.n("frames_x").toInt(); val rows = p.n("frames_y").toInt()
                require(cols >= 1 && rows >= 1 && ctx.size % cols == 0 && ctx.size % rows == 0) { "sprite frames_x/frames_y must divide size" }
                val cell = min(cols * rows - 1, (t * cols * rows).toInt()); val w = ctx.size / cols; val h = ctx.size / rows
                val angleRad = p.n("sprite_spin_deg_per_sec") * age / 20 * PI / 180
                raster(ctx.size) { px, py ->
                    val dx = px - x; val dy = py - y
                    val u = if (r <= 0) -1.0 else (cos(angleRad) * dx + sin(angleRad) * dy) / (2 * r) + 0.5
                    val v = if (r <= 0) -1.0 else (-sin(angleRad) * dx + cos(angleRad) * dy) / (2 * r) + 0.5
                    if (u !in 0.0..1.0 || v !in 0.0..1.0) FloatArray(4) else sample(sprite, cell % cols * w + u * w, cell / cols * h + v * h)
                }
            }
            out = blend(alpha(stamp, a), out, "over")
        }
        return out
    }
}
