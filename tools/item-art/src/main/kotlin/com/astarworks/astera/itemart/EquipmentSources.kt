package com.astarworks.astera.itemart

import org.yaml.snakeyaml.Yaml
import java.awt.Image
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Generated surface tiles -> vanilla equipment UVs. No drawing or Mojang pixels in this pipeline. */
internal object EquipmentSources {
    fun renderAll(content: Path): Map<String, ByteArray> {
        val manifest = content.resolve("textures/sources/equipment.yaml")
        if (!Files.isRegularFile(manifest)) return emptyMap()
        val spec = Yaml().load<Map<String, Any>>(Files.readString(manifest))
        val image = requireNotNull(ImageIO.read(content.resolve("textures/sources/${spec["file"]}").toFile()))
        val sets = (spec["sets"] as List<*>).map { it.toString() }
        require(image.width * sets.size == image.height * 6) { "Equipment sheet must have six square tiles per set" }
        return buildMap {
            sets.forEachIndexed { row, id ->
                val tiles = (0..5).map { col -> image.getSubimage(
                    col * image.width / 6, row * image.height / sets.size,
                    image.width / 6, image.height / sets.size,
                ) }
                fun atlas(leggings: Boolean): BufferedImage {
                    val out = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
                    fun face(tile: Int, x: Int, y: Int, w: Int, h: Int, startY: Int = 0) {
                        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        val g = scaled.createGraphics()
                        g.drawImage(tiles[tile].getScaledInstance(w, h, Image.SCALE_REPLICATE), 0, 0, null)
                        g.dispose()
                        for (v in startY until h) for (u in 0 until w) out.setRGB(x + u, y + v, scaled.getRGB(u, v))
                    }
                    // ModelPart cube UV net: right, front, left, back; top and bottom above them.
                    fun cube(u: Int, v: Int, w: Int, h: Int, d: Int, front: Int, back: Int, side: Int, startY: Int = 0) {
                        face(side, u, v + d, d, h, startY)
                        face(front, u + d, v + d, w, h, startY)
                        face(side, u + d + w, v + d, d, h, startY)
                        face(back, u + d * 2 + w, v + d, w, h, startY)
                        if (startY == 0) face(side, u + d, v, w, d)
                        face(side, u + d + w, v, w, d)
                    }
                    if (leggings) {
                        // Leggings alone render a waist band, never a second chestplate.
                        cube(16, 16, 8, 12, 4, 2, 3, 5, startY = 9)
                        cube(0, 16, 4, 12, 4, 5, 5, 5)
                    } else {
                        cube(0, 0, 8, 8, 8, 0, 1, 1)
                        cube(16, 16, 8, 12, 4, 2, 3, 3)
                        cube(40, 16, 4, 12, 4, 4, 4, 4)
                        // Only boots occupy the outer leg layer; trousers belong to the inner layer.
                        cube(0, 16, 4, 12, 4, 5, 5, 5, startY = 7)
                    }
                    val raster = PixelConverter.Raster.of(out)
                    val palette = PixelConverter.palette(raster, 24)
                    val labs = palette.map(PixelConverter::lab)
                    for (y in 0 until 32) for (x in 0 until 64) {
                        val color = out.getRGB(x, y)
                        if (color ushr 24 == 0) continue
                        val lab = PixelConverter.lab(color)
                        val nearest = labs.indices.minBy { i -> labs[i].indices.sumOf { k ->
                            val delta = labs[i][k] - lab[k]; delta * delta
                        } }
                        out.setRGB(x, y, palette[nearest])
                    }
                    return out
                }
                for (layer in listOf("humanoid", "humanoid_leggings")) {
                    put("textures/equipment/$layer/$id.png", ItemArt.png(atlas(layer == "humanoid_leggings")))
                }
            }
        }
    }
}
