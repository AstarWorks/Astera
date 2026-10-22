package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Draws the full-screen cut-in overlay glyphs (`screen_flash`, no AI assets, ADR-0010) and the
 * `astera:overlay` font that maps them to code points. Rendered per pixel with plain math (no Java2D
 * rasterizer), so the output is identical on every JVM and the committed copy can be compared exactly.
 *
 * Output under `content/hud/`:
 *  - `overlay/<name>.png`  white-on-alpha textures (the title colour tints them, so grey levels act as
 *    brightness and alpha as opacity)
 *  - `overlay.json`        the font fragment; [ResourcePackBuilder] ships it as `assets/astera/font/overlay.json`
 *
 * Screen coverage: see [ScreenOverlay] (title = 4x GUI scale; glyph height H covers 4*H GUI pixels;
 * `ascent = H/2 - 3` centres it on the screen). Every texture keeps a faint (alpha 1) pixel column on
 * its right edge so the bitmap provider's advance is the full width and the glyph centres horizontally.
 */
public object OverlayArtGenerator {

    private const val SIZE = 256
    private const val SOLID_SIZE = 16

    /** Relative path under `content/hud/` to file bytes. */
    public fun render(): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        for (overlay in ScreenOverlay.entries) out["overlay/${overlay.yamlName}.png"] = png(texture(overlay))
        out["overlay.json"] = fontJson().toByteArray()
        return out
    }

    internal fun texture(overlay: ScreenOverlay): BufferedImage {
        val size = if (overlay == ScreenOverlay.WHITE) SOLID_SIZE else SIZE
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) for (x in 0 until size) {
            // u, v in [-1, 1] over pixel centres, y down.
            val u = (x + 0.5) / size * 2.0 - 1.0
            val v = (y + 0.5) / size * 2.0 - 1.0
            val (grey, alpha) = when (overlay) {
                ScreenOverlay.WHITE -> 1.0 to 1.0
                ScreenOverlay.VIGNETTE -> 1.0 to vignetteAlpha(u, v)
                ScreenOverlay.GOLD_BURST -> goldBurst(u, v)
                ScreenOverlay.SPEED_DIAG -> 1.0 to speedDiagAlpha(u, v)
                ScreenOverlay.SPEED_RADIAL -> 1.0 to speedRadialAlpha(u, v)
                ScreenOverlay.IMPACT_STAR -> 1.0 to impactStarAlpha(u, v)
            }
            image.setRGB(x, y, argb(grey, alpha))
        }
        // Advance marker: last column never fully transparent.
        for (y in 0 until size) {
            val argb = image.getRGB(size - 1, y)
            if ((argb ushr 24) == 0) image.setRGB(size - 1, y, 0x01FFFFFF)
        }
        return image
    }

    private fun argb(grey: Double, alpha: Double): Int {
        val g = (grey.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        val a = (alpha.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (a shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun smooth(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** Transparent centre, opaque corners: dark edge vignette when tinted black. */
    private fun vignetteAlpha(u: Double, v: Double): Double {
        val r = StrictMath.sqrt(u * u + v * v) / StrictMath.sqrt(2.0)
        return smooth(0.35, 0.95, r)
    }

    /** Bright centre fading to a darker, more transparent rim: tinted gold it reads as a finisher burst. */
    private fun goldBurst(u: Double, v: Double): Pair<Double, Double> {
        val r = (StrictMath.sqrt(u * u + v * v) / StrictMath.sqrt(2.0)).coerceIn(0.0, 1.0)
        val grey = 1.0 - 0.65 * smooth(0.0, 1.0, r)
        val alpha = 0.55 + 0.4 * (1.0 - smooth(0.0, 0.8, r))
        return grey to alpha
    }

    /** 45-degree speed lines of varying thickness, only near the frame (centre stays clear). */
    private fun speedDiagAlpha(u: Double, v: Double): Double {
        val along = (u + v) * 0.5          // position across the stripes
        val cell = StrictMath.floor((along + 2.0) * 14.0)
        val frac = (along + 2.0) * 14.0 - cell
        val thickness = 0.12 + 0.5 * hash(cell.toInt())      // per-stripe thickness
        val present = if (hash(cell.toInt() + 977) > 0.25) 1.0 else 0.0
        val stripe = (1.0 - smooth(thickness * 0.5, thickness * 0.5 + 0.05, StrictMath.abs(frac - 0.5))) * present
        val edge = smooth(0.35, 0.9, StrictMath.max(StrictMath.abs(u), StrictMath.abs(v)))
        return stripe * edge
    }

    /** Spokes radiating from the centre, tapering outwards, clear in the middle. */
    private fun speedRadialAlpha(u: Double, v: Double): Double {
        val r = StrictMath.sqrt(u * u + v * v)
        if (r < 0.001) return 0.0
        val spokes = 96
        val angle = (StrictMath.atan2(v, u) / (2.0 * StrictMath.PI) + 0.5) * spokes
        val cell = StrictMath.floor(angle)
        val frac = angle - cell
        val strength = hash(cell.toInt() * 7 + 13)
        val halfWidth = 0.10 + 0.25 * strength
        val spoke = 1.0 - smooth(halfWidth, halfWidth + 0.08, StrictMath.abs(frac - 0.5))
        val present = if (strength > 0.2) 1.0 else 0.0
        val radial = smooth(0.25, 0.55, r) * (1.0 - 0.15 * smooth(0.9, 1.4, r))
        return spoke * present * radial
    }

    /** Manga-style impact frame: opaque everywhere except a jagged star-shaped hole. */
    private fun impactStarAlpha(u: Double, v: Double): Double {
        val r = StrictMath.sqrt(u * u + v * v)
        val angle = StrictMath.atan2(v, u)
        val points = 14.0
        val cell = StrictMath.floor((angle / (2.0 * StrictMath.PI) + 0.5) * points).toInt()
        val phase = ((angle / (2.0 * StrictMath.PI) + 0.5) * points) - cell
        val tip = 0.55 + 0.4 * hash(cell + 31)                 // radius of this spike
        val tri = 1.0 - StrictMath.abs(phase * 2.0 - 1.0)       // 0 at valley, 1 at the tip
        val radius = 0.22 + (tip - 0.22) * tri * tri            // concave spikes
        val hole = 1.0 - smooth(radius - 0.015, radius + 0.015, r)
        return 0.92 * (1.0 - hole)
    }

    /** Deterministic [0, 1) noise from an integer. */
    private fun hash(n: Int): Double {
        var h = n * -0x61c88647 // golden-ratio multiplier
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        h = h xor (h ushr 12)
        h *= 0x297a2d39
        h = h xor (h ushr 15)
        return (h and 0xFFFFFF) / 16777216.0
    }

    internal fun fontJson(): String {
        val providers = ScreenOverlay.entries.map { overlay ->
            linkedMapOf(
                "type" to "bitmap",
                "file" to "astera:hud/overlay/${overlay.yamlName}.png",
                "ascent" to ScreenOverlay.ascentFor(overlay.height),
                "height" to overlay.height,
                "chars" to listOf(overlay.glyph.toString()),
            )
        }
        val root = linkedMapOf(
            "_comment" to "Full-screen cut-in overlays (screen_flash). GENERATED by :tools:resource-pack generateHudArt " +
                "from ScreenOverlay - do not edit. Font astera:overlay, PUA E900-E90F.",
            "providers" to providers,
        )
        val printer = DefaultPrettyPrinter().also { it.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE) }
        val out = java.io.StringWriter()
        val generator = mapper.factory.createGenerator(out).setPrettyPrinter(printer)
        mapper.writeValue(generator, root)
        return out.toString().replace("\r\n", "\n") + "\n"
    }

    private val mapper = JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII)
        .build()

    private fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }
}
