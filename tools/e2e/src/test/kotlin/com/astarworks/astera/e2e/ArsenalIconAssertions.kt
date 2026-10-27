package com.astarworks.astera.e2e

import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat

/** Check the delivered pixels, including opaque key blends and black silhouette edges. */
internal fun assertCleanArsenalIcon(id: String, png: ByteArray) {
    val image = ImageIO.read(png.inputStream())
    for (y in 0 until image.height) for (x in 0 until image.width) {
        val c = image.getRGB(x, y)
        if (c ushr 24 == 0) continue
        val r = c shr 16 and 255
        val g = c shr 8 and 255
        val b = c and 255
        assertThat(minOf(r, b) > g + 12 && minOf(r, b) > g * 1.25)
            .describedAs("$id key-colour fringe at ($x,$y)").isFalse()
        val edge = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (nx, ny) ->
            nx !in 0 until image.width || ny !in 0 until image.height || image.getRGB(nx, ny) ushr 24 == 0
        }
        if (edge) {
            assertThat(maxOf(r, g, b)).describedAs("$id black rim at ($x,$y)").isGreaterThanOrEqualTo(30)
            assertThat(c and 0xFFFFFF).describedAs("$id shared black outline at ($x,$y)").isNotEqualTo(0x16111F)
        }
    }
}
