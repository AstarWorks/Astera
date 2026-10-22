package com.astarworks.astera.itemart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class PixelConverterTest {

    private val magenta = Color(0xFF00FF)

    /** A 256x256 "AI cell": flat magenta, a diagonal blade with a dark rim and a highlight stripe. */
    private fun blade(): BufferedImage {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = magenta; g.fillRect(0, 0, 256, 256)
        g.rotate(-Math.PI / 4, 128.0, 128.0)
        g.color = Color(0x101018); g.fillRect(20, 108, 216, 40)
        g.color = Color(0x8C96A8); g.fillRect(26, 114, 204, 28)
        g.color = Color(0xF4F8FC); g.fillRect(26, 118, 204, 6)
        g.dispose()
        return img
    }

    @Test
    fun `a keyed raster becomes an outlined 32x32 icon with no background left`() {
        val out = PixelConverter.convert(blade())
        assertThat(out.width).isEqualTo(Sprite.SIZE)
        assertThat(out.height).isEqualTo(Sprite.SIZE)
        val px = ItemArt.pixels(out)
        assertThat(px.count { it ushr 24 == 0xFF }).isGreaterThan(60)
        assertThat(px).noneMatch { it and 0xFFFFFF == 0xFF00FF && it ushr 24 != 0 }
        assertThat(px.filter { it ushr 24 != 0 }.toSet().size).isLessThanOrEqualTo(17)
        assertThat(px).contains(Sprite.OUTLINE)
        assertThat(px.map { it ushr 24 }.toSet()).isSubsetOf(0, 0xFF)
    }

    @Test
    fun `conversion is deterministic`() {
        assertThat(ItemArt.pixels(PixelConverter.convert(blade()))).isEqualTo(ItemArt.pixels(PixelConverter.convert(blade())))
    }

    @Test
    fun `the highlight survives the downscale instead of blurring away`() {
        val px = ItemArt.pixels(PixelConverter.convert(blade()))
        assertThat(px).anyMatch { c -> c ushr 24 != 0 && PixelConverter.lab(c)[0] > 90 }
    }

    @Test
    fun `a sheet cell takes the blobs centred in it, even when they cross the grid line`(@TempDir dir: Path) {
        val sheet = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = magenta; g.fillRect(0, 0, 200, 100)
        g.color = Color(0x3060C0); g.fillRect(20, 30, 95, 40) // centred in cell 0, crosses x=100
        g.color = Color(0xC03030); g.fillRect(150, 30, 30, 40) // cell 1
        g.dispose()
        Files.createDirectories(dir.resolve(Sources.DIR))
        ImageIO.write(sheet, "png", dir.resolve("${Sources.DIR}/s.png").toFile())
        Files.writeString(
            dir.resolve(Sources.MANIFEST),
            "sources:\n  s: { file: s.png, grid: [2, 1], cells: [a, b] }\nitems:\n  a: s\n  b: { from: s, colors: 4, model: handheld }\n",
        )
        val refs = Sources.load(dir)
        assertThat(refs.getValue("b").colors).isEqualTo(4)
        assertThat(refs.getValue("b").model).isEqualTo(ModelStyle.HANDHELD)
        val a = Sources.crop(refs.getValue("a"))
        assertThat(a.width).isEqualTo(95)
        assertThat(ItemArt.pixels(a).filter { it ushr 24 != 0 }.map { it and 0xFFFFFF }.toSet()).containsExactly(0x3060C0)
        assertThat(Sources.crop(refs.getValue("b")).width).isEqualTo(30)
    }

    @Test
    fun `no manifest means every item stays code-drawn`(@TempDir dir: Path) {
        assertThat(Sources.load(dir)).isEmpty()
    }
}
