package com.astarworks.astera.vfxpreview

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** A sprite glyph found in a MiniMessage text: `<font:astera:vfx/<id>>` + char U+E000 + [frame]. */
data class SpriteRef(val id: String, val frame: Int)

/**
 * Frames of `content/textures/generated/sprites/<id>.png` (ADR-0237): a horizontal strip of
 * square `size`-px frames, `size` = strip height. Frames are tinted (multiply) and cached.
 */
class SpriteStrips(private val contentDir: File) {

    private val strips = HashMap<String, BufferedImage?>()
    private val tinted = HashMap<Triple<String, Int, Int>, BufferedImage?>()

    /** Frame image (tinted by [rgb], white = unchanged) or `null` when the strip / frame does not exist. */
    fun frame(ref: SpriteRef, rgb: Int): BufferedImage? = tinted.getOrPut(Triple(ref.id, ref.frame, rgb and 0xFFFFFF)) {
        val strip = strip(ref.id) ?: return@getOrPut null
        val size = strip.height
        if ((ref.frame + 1) * size > strip.width) return@getOrPut null
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val tr = (rgb shr 16) and 0xFF
        val tg = (rgb shr 8) and 0xFF
        val tb = rgb and 0xFF
        for (y in 0 until size) for (x in 0 until size) {
            val p = strip.getRGB(ref.frame * size + x, y)
            val a = p ushr 24
            val r = ((p shr 16) and 0xFF) * tr / 255
            val g = ((p shr 8) and 0xFF) * tg / 255
            val b = (p and 0xFF) * tb / 255
            out.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
        out
    }

    private fun strip(id: String): BufferedImage? = strips.getOrPut(id) {
        val file = File(contentDir, "textures/generated/sprites/$id.png")
        if (file.isFile) ImageIO.read(file) else null
    }

    companion object {
        /** Content dir for callers that do not pass one: `-Dastera.contentDir` (tests) or `./content`. */
        fun default(): SpriteStrips = SpriteStrips(File(System.getProperty("astera.contentDir") ?: "content"))
    }
}
