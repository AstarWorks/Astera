package com.astarworks.astera.itemart

import java.awt.image.BufferedImage
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns any raster (an AI sheet cell, a hand-drawn PNG, a high-res render) into a [Sprite.SIZE] icon that
 * sits next to the code-drawn ones (ADR-0238). Deterministic, so the output can be checked in `check`.
 *
 * Stages (kept in [Stages] for the `convert` debug command):
 *  1. normalize   — key out the background colour flood-filled from the border, drop specks, crop
 *  2. smooth      — 3x3 median on opaque pixels when the source is much larger than the target
 *  3. palette     — k-means in Lab over a colour histogram, seeded by farthest point (no randomness)
 *  4. pixelize    — per target pixel: opaque by coverage; colour = palette entry that wins a vote weighted
 *                   by contrast to the cell mean, so outlines / highlights survive instead of blurring
 *  5. cleanup     — drop orphan pixels, fill pinholes, merge low-contrast single-pixel noise
 *  6. outline     — shared dark silhouette, or an opt-in single-pixel material shade on the existing edge
 */
internal object PixelConverter {

    data class Options(
        val colors: Int = 16,
        val size: Int = Sprite.SIZE,
        val margin: Int = 1,
        /** Dark outline around the silhouette (items yes, particle sprites no). */
        val outline: Boolean = true,
        /** Share of a target pixel's source area that must be opaque for it to be drawn. */
        val coverage: Double = COVERAGE,
        /** Keep single pixels with no neighbour (embers, droplets). */
        val keepOrphans: Boolean = false,
        /** Magenta-screen item sheets: remove tinted blends before palette selection. */
        val removeMagenta: Boolean = false,
        /** Recolour the existing outer black band instead of adding a black silhouette. */
        val materialOutline: Boolean = false,
    )

    class Stages(
        val normalized: BufferedImage,
        val smoothed: BufferedImage,
        val palette: IntArray,
        val pixelized: BufferedImage,
        val cleaned: BufferedImage,
        val result: BufferedImage,
    )

    fun convert(source: BufferedImage, options: Options = Options()): BufferedImage = stages(source, options).result

    fun stages(source: BufferedImage, options: Options = Options()): Stages {
        val raw = Raster.of(source)
        if (options.removeMagenta) for (i in raw.px.indices) if (isKeyMagenta(raw.px[i])) raw.px[i] = 0
        val normalized = normalize(raw)
        val smoothed = if (max(normalized.w, normalized.h) >= options.size * 4) median(normalized) else normalized
        val palette = palette(smoothed, options.colors)
        val pixelized = pixelize(smoothed, palette, options)
        val cleaned = cleanup(pixelized, options.keepOrphans)
        val result = when {
            options.materialOutline -> materialOutline(cleaned)
            options.outline -> outline(cleaned)
            else -> cleaned
        }
        return Stages(normalized.image(), smoothed.image(), palette, pixelized.image(), cleaned.image(), result.image())
    }

    /** ARGB pixels with helpers; the converter's working surface. */
    internal class Raster(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
        operator fun get(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) px[y * w + x] else 0
        operator fun set(x: Int, y: Int, v: Int) { px[y * w + x] = v }
        fun opaque(x: Int, y: Int): Boolean = this[x, y] ushr 24 != 0
        fun copy(): Raster = Raster(w, h, px.copyOf())
        fun image(): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, w, h, px, 0, w) }

        companion object {
            fun of(img: BufferedImage): Raster =
                Raster(img.width, img.height).also { img.getRGB(0, 0, img.width, img.height, it.px, 0, img.width) }
        }
    }

    // ---- 1. normalize --------------------------------------------------------------------------------

    private const val BG_DELTA = 22.0
    private const val FRINGE_DELTA = 40.0
    private const val SPECK_RATIO = 0.02

    internal fun normalize(src: Raster): Raster {
        val r = keyBackground(src)
        dropSpecks(r)
        return crop(r)
    }

    /**
     * Binary alpha, then the background colour (when the border is mostly one colour) becomes transparent:
     * everywhere within [BG_DELTA] (enclosed holes too), plus anti-aliased fringe still tinted by it.
     */
    internal fun keyBackground(src: Raster): Raster {
        val r = src.copy()
        for (i in r.px.indices) if (r.px[i] ushr 24 < 128) r.px[i] = 0 else r.px[i] = r.px[i] or OPAQUE
        val bgLab = borderColor(r)?.let { lab(it) } ?: return r
        for (i in r.px.indices) if (r.px[i] ushr 24 != 0 && deltaE(lab(r.px[i]), bgLab) <= BG_DELTA) r.px[i] = 0
        repeat(FRINGE_PASSES) {
            val fringe = (0 until r.w * r.h).filter { i ->
                r.px[i] ushr 24 != 0 && deltaE(lab(r.px[i]), bgLab) < FRINGE_DELTA &&
                    neighbours4(i % r.w, i / r.w).any { (nx, ny) -> !r.opaque(nx, ny) }
            }
            for (i in fringe) r.px[i] = 0
        }
        return r
    }

    private const val FRINGE_PASSES = 2

    /** The flat background colour of [src] as [keyBackground] sees it, or null when the border is mixed. */
    internal fun sheetBackground(src: Raster): Int? {
        val r = src.copy()
        for (i in r.px.indices) if (r.px[i] ushr 24 < 128) r.px[i] = 0 else r.px[i] = r.px[i] or OPAQUE
        return borderColor(r)
    }

    /** The colour most border pixels are close to, when at least [BG_SHARE] of the border is. */
    internal fun borderColor(r: Raster): Int? {
        val border = ArrayList<Int>()
        for (x in 0 until r.w) { border.add(r[x, 0]); border.add(r[x, r.h - 1]) }
        for (y in 0 until r.h) { border.add(r[0, y]); border.add(r[r.w - 1, y]) }
        val opaque = border.filter { it ushr 24 != 0 }
        val bin = opaque.groupingBy { it and 0xF0F0F0 }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val members = opaque.filter { it and 0xF0F0F0 == bin }
        val mean = DoubleArray(3)
        for (c in members) { val l = lab(c); for (d in 0..2) mean[d] += l[d] / members.size }
        val close = opaque.count { deltaE(lab(it), mean) <= BG_DELTA }
        return if (close >= border.size * BG_SHARE) fromLab(mean) else null
    }

    private const val BG_SHARE = 0.4

    internal fun dropSpecks(r: Raster) {
        val (label, comps) = components(r)
        val biggest = comps.maxOfOrNull { it.size } ?: return
        for (i in label.indices) if (label[i] >= 0 && comps[label[i]].size < biggest * SPECK_RATIO) r.px[i] = 0
    }

    /** An 8-connected opaque blob: pixel count and centroid. */
    internal class Blob(val size: Int, val cx: Double, val cy: Double)

    /** Blob index per pixel (-1 = transparent) and the blobs. */
    internal fun components(r: Raster): Pair<IntArray, List<Blob>> {
        val label = IntArray(r.w * r.h) { -1 }
        val blobs = ArrayList<Blob>()
        val stack = ArrayDeque<Int>()
        for (start in label.indices) {
            if (label[start] != -1 || r.px[start] ushr 24 == 0) continue
            val id = blobs.size
            var n = 0; var sx = 0.0; var sy = 0.0
            stack.add(start)
            label[start] = id
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val x = i % r.w; val y = i / r.w
                n++; sx += x; sy += y
                for ((nx, ny) in neighbours8(x, y)) {
                    if (nx !in 0 until r.w || ny !in 0 until r.h) continue
                    val j = ny * r.w + nx
                    if (label[j] == -1 && r.px[j] ushr 24 != 0) { label[j] = id; stack.add(j) }
                }
            }
            blobs.add(Blob(n, sx / n, sy / n))
        }
        return label to blobs
    }

    internal fun crop(r: Raster): Raster {
        var x0 = r.w; var y0 = r.h; var x1 = -1; var y1 = -1
        for (y in 0 until r.h) for (x in 0 until r.w) if (r.opaque(x, y)) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        require(x1 >= 0) { "source has no visible pixels after background removal" }
        val out = Raster(x1 - x0 + 1, y1 - y0 + 1)
        for (y in 0 until out.h) for (x in 0 until out.w) out[x, y] = r[x + x0, y + y0]
        return out
    }

    /**
     * Converts the frames of one animation (already background-keyed, e.g. cells of a [Sources.Sheet]) so they
     * stay registered: ONE union bounding box crops every frame, ONE palette comes from all of them, and
     * every frame is scaled and placed the same, so a moving blob keeps its offset between frames.
     * Blobs of 1-2 source pixels are dropped as noise; anything bigger (embers, shards, droplets) is content.
     */
    fun convertFrames(frames: List<BufferedImage>, options: Options): List<BufferedImage> {
        require(frames.isNotEmpty()) { "no frames" }
        val rasters = frames.map { f ->
            val r = Raster.of(f)
            for (i in r.px.indices) r.px[i] = if (r.px[i] ushr 24 < 128) 0 else r.px[i] or OPAQUE
            val (label, blobs) = components(r)
            for (i in label.indices) if (label[i] >= 0 && blobs[label[i]].size <= TINY_BLOB) r.px[i] = 0
            r
        }
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (r in rasters) for (y in 0 until r.h) for (x in 0 until r.w) if (r.opaque(x, y)) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        require(x1 >= 0) { "animation has no visible pixels after background removal" }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val cropped = rasters.map { r ->
            Raster(w, h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x, y] = r[x + x0, y + y0] }
        }
        val smoothed = if (max(w, h) >= options.size * 4) cropped.map { median(it) } else cropped
        val all = Raster(w, h * smoothed.size)
        smoothed.forEachIndexed { i, r -> System.arraycopy(r.px, 0, all.px, i * w * h, w * h) }
        val palette = palette(all, options.colors)
        return smoothed.map { r ->
            val cleaned = cleanup(pixelize(r, palette, options), options.keepOrphans)
            (if (options.outline) outline(cleaned) else cleaned).image()
        }
    }

    private const val TINY_BLOB = 2

    // ---- 2. smooth -----------------------------------------------------------------------------------

    internal fun median(r: Raster): Raster {
        val out = r.copy()
        val rs = IntArray(9); val gs = IntArray(9); val bs = IntArray(9)
        for (y in 0 until r.h) for (x in 0 until r.w) {
            if (!r.opaque(x, y)) continue
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val c = r[x + dx, y + dy]
                if (c ushr 24 == 0) continue
                rs[n] = c shr 16 and 0xFF; gs[n] = c shr 8 and 0xFF; bs[n] = c and 0xFF; n++
            }
            rs.sort(0, n); gs.sort(0, n); bs.sort(0, n)
            out[x, y] = OPAQUE or (rs[n / 2] shl 16) or (gs[n / 2] shl 8) or bs[n / 2]
        }
        return out
    }

    // ---- 3. palette ----------------------------------------------------------------------------------

    internal fun palette(r: Raster, colors: Int): IntArray {
        val hist = HashMap<Int, Int>()
        for (c in r.px) if (c ushr 24 != 0) hist.merge(c and 0xFCFCFC, 1, Int::plus)
        val bins = hist.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        val labs = bins.map { lab(it.key or OPAQUE) }
        val weights = bins.map { it.value.toDouble() }
        val k = min(colors, bins.size)
        // Farthest-point seeding from the most frequent colour, weighted by sqrt(frequency).
        val centers = ArrayList<DoubleArray>().apply { add(labs[0].copyOf()) }
        val nearest = DoubleArray(labs.size) { deltaE(labs[it], centers[0]) }
        while (centers.size < k) {
            var best = -1; var bestScore = -1.0
            for (i in labs.indices) {
                val s = nearest[i] * sqrt(weights[i])
                if (s > bestScore) { bestScore = s; best = i }
            }
            centers.add(labs[best].copyOf())
            for (i in labs.indices) nearest[i] = min(nearest[i], deltaE(labs[i], centers.last()))
        }
        repeat(KMEANS_ROUNDS) {
            val sum = Array(k) { DoubleArray(3) }
            val wsum = DoubleArray(k)
            for (i in labs.indices) {
                val c = closest(labs[i], centers)
                for (d in 0..2) sum[c][d] += labs[i][d] * weights[i]
                wsum[c] += weights[i]
            }
            for (c in 0 until k) if (wsum[c] > 0) for (d in 0..2) centers[c][d] = sum[c][d] / wsum[c]
        }
        return centers.map { fromLab(it) }.distinct().sortedBy { lab(it)[0] }.toIntArray()
    }

    private const val KMEANS_ROUNDS = 12

    // ---- 4. pixelize ---------------------------------------------------------------------------------

    private const val COVERAGE = 0.42

    internal fun pixelize(r: Raster, palette: IntArray, o: Options): Raster {
        val inner = o.size - 2 * o.margin
        val scale = max(r.w, r.h).toDouble() / inner
        val tw = max(1, (r.w / scale).roundToInt())
        val th = max(1, (r.h / scale).roundToInt())
        val ox = o.margin + (inner - tw) / 2
        val oy = o.margin + (inner - th) / 2
        val pLab = palette.map { lab(it) }
        val index = IntArray(r.w * r.h) { i -> if (r.px[i] ushr 24 == 0) -1 else closest(lab(r.px[i]), pLab) }
        val out = Raster(o.size, o.size)
        val votes = DoubleArray(palette.size)
        for (ty in 0 until th) for (tx in 0 until tw) {
            val sx0 = (tx * scale).toInt(); val sx1 = max(sx0 + 1, min(r.w, ((tx + 1) * scale).toInt()))
            val sy0 = (ty * scale).toInt(); val sy1 = max(sy0 + 1, min(r.h, ((ty + 1) * scale).toInt()))
            votes.fill(0.0)
            var opaque = 0; var total = 0
            val mean = DoubleArray(3)
            for (y in sy0 until min(sy1, r.h)) for (x in sx0 until min(sx1, r.w)) {
                total++
                val p = index[y * r.w + x]
                if (p < 0) continue
                opaque++; votes[p] += 1.0
                for (d in 0..2) mean[d] += pLab[p][d]
            }
            if (total == 0 || opaque.toDouble() / total < o.coverage) continue
            for (d in 0..2) mean[d] /= opaque
            var best = 0; var bestScore = -1.0
            for (p in palette.indices) {
                if (votes[p] == 0.0) continue
                val s = votes[p] * (1.0 + deltaE(pLab[p], mean) / CONTRAST_BOOST)
                if (s > bestScore) { bestScore = s; best = p }
            }
            out[ox + tx, oy + ty] = palette[best]
        }
        return out
    }

    private const val CONTRAST_BOOST = 35.0

    // ---- 5. cleanup ----------------------------------------------------------------------------------

    private const val NOISE_DELTA = 14.0

    internal fun cleanup(src: Raster, keepOrphans: Boolean = false): Raster {
        val r = src.copy()
        for (y in 0 until r.h) for (x in 0 until r.w) {
            val n4 = neighbours4(x, y).count { (nx, ny) -> src.opaque(nx, ny) }
            if (!keepOrphans && src.opaque(x, y) && n4 == 0 && neighbours8(x, y).none { (nx, ny) -> src.opaque(nx, ny) }) r[x, y] = 0
            if (!src.opaque(x, y) && n4 == 4) r[x, y] = mode(neighbours4(x, y).map { (nx, ny) -> src[nx, ny] })
        }
        val pass = r.copy()
        for (y in 0 until r.h) for (x in 0 until r.w) {
            val c = pass[x, y]
            if (c ushr 24 == 0) continue
            val around = neighbours8(x, y).map { (nx, ny) -> pass[nx, ny] }.filter { it ushr 24 != 0 }
            if (around.size < 5 || c in around) continue
            val m = mode(around)
            if (deltaE(lab(c), lab(m)) < NOISE_DELTA) r[x, y] = m
        }
        return r
    }

    // ---- 6. outline ----------------------------------------------------------------------------------

    private const val DARK_L = 22.0

    internal fun outline(src: Raster): Raster {
        val r = src.copy()
        val edge = (0 until r.w * r.h).filter { i ->
            src.px[i] ushr 24 != 0 && neighbours4(i % r.w, i / r.w).any { (nx, ny) -> !src.opaque(nx, ny) }
        }
        val dark = edge.filter { lab(src.px[it])[0] < DARK_L }.toSet()
        for (i in dark) r.px[i] = Sprite.OUTLINE
        for (y in 0 until r.h) for (x in 0 until r.w) {
            if (src.opaque(x, y)) continue
            val touchesLight = neighbours4(x, y).any { (nx, ny) ->
                src.opaque(nx, ny) && (ny * r.w + nx) !in dark
            }
            if (touchesLight) r[x, y] = Sprite.OUTLINE
        }
        return r
    }

    /** Includes dark/desaturated antialias blends, not just RGB distance to #ff00ff. */
    internal fun isKeyMagenta(c: Int): Boolean {
        if (c ushr 24 == 0) return false
        val red = c shr 16 and 255
        val green = c shr 8 and 255
        val blue = c and 255
        return min(red, blue) > green + 12 && min(red, blue) > green * 1.25
    }

    /** Only the near-black band connected to transparency is an outline; enclosed dark detail stays. */
    internal fun materialOutline(src: Raster): Raster {
        fun nearBlack(c: Int) = c ushr 24 != 0 && maxOf(c shr 16 and 255, c shr 8 and 255, c and 255) < 52
        fun edge(i: Int) = neighbours4(i % src.w, i / src.w).any { (x, y) -> !src.opaque(x, y) }
        val band = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        for (i in src.px.indices) if (nearBlack(src.px[i]) && edge(i)) { band.add(i); queue.add(i) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for ((x, y) in neighbours4(i % src.w, i / src.w)) {
                if (x !in 0 until src.w || y !in 0 until src.h) continue
                val j = y * src.w + x
                if (nearBlack(src.px[j]) && band.add(j)) queue.add(j)
            }
        }
        val material = src.px.indices.filter { src.px[it] ushr 24 != 0 && !nearBlack(src.px[it]) }
        require(material.isNotEmpty()) { "no material colour for outline" }
        val out = src.copy()
        for (i in band) {
            val nearest = material.minBy { j ->
                val dx = i % src.w - j % src.w
                val dy = i / src.w - j / src.w
                dx * dx + dy * dy
            }
            val c = src.px[nearest]
            // Only the outermost pixel gets the dark shade; thicker source bands become material.
            val shade = if (edge(i)) 0.60 else 1.0
            out.px[i] = OPAQUE or (((c shr 16 and 255) * shade).roundToInt() shl 16) or
                (((c shr 8 and 255) * shade).roundToInt() shl 8) or ((c and 255) * shade).roundToInt()
        }
        return out
    }

    // ---- colour math ---------------------------------------------------------------------------------

    private const val OPAQUE = 0xFF000000.toInt()

    private val labCache = HashMap<Int, DoubleArray>()

    internal fun lab(argb: Int): DoubleArray = labCache.getOrPut(argb and 0xFFFFFF) {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
        val r = lin(argb shr 16 and 0xFF); val g = lin(argb shr 8 and 0xFF); val b = lin(argb and 0xFF)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun fromLab(l: DoubleArray): Int {
        val fy = (l[0] + 16) / 116; val fx = fy + l[1] / 500; val fz = fy - l[2] / 200
        fun inv(t: Double) = if (t.pow(3) > 0.008856) t.pow(3) else (t - 16.0 / 116) / 7.787
        val x = inv(fx) * 0.95047; val y = inv(fy); val z = inv(fz) * 1.08883
        fun gam(v: Double): Int {
            val c = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
            return (c * 255).roundToInt().coerceIn(0, 255)
        }
        val r = gam(3.2406 * x - 1.5372 * y - 0.4986 * z)
        val g = gam(-0.9689 * x + 1.8758 * y + 0.0415 * z)
        val b = gam(0.0557 * x - 0.2040 * y + 1.0570 * z)
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private fun deltaE(a: DoubleArray, b: DoubleArray): Double {
        val dl = a[0] - b[0]; val da = a[1] - b[1]; val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun closest(c: DoubleArray, centers: List<DoubleArray>): Int {
        var best = 0; var d = Double.MAX_VALUE
        for (i in centers.indices) { val e = deltaE(c, centers[i]); if (e < d) { d = e; best = i } }
        return best
    }

    private fun mode(colors: List<Int>): Int =
        colors.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key

    internal fun neighbours4(x: Int, y: Int) = listOf(x to y - 1, x - 1 to y, x + 1 to y, x to y + 1)

    internal fun neighbours8(x: Int, y: Int) =
        listOf(x - 1 to y - 1, x to y - 1, x + 1 to y - 1, x - 1 to y, x + 1 to y, x - 1 to y + 1, x to y + 1, x + 1 to y + 1)
}
