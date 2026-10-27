package com.astarworks.astera.itemart

import com.astarworks.astera.core.domain.item.Rarity
import net.kyori.adventure.text.minimessage.MiniMessage
import org.yaml.snakeyaml.Yaml
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Nine-slice tooltip art from the generated sheet; colour is read from the shipped lore. */
internal object TooltipArt {
    private const val DIR = "textures/gui/sprites/tooltip"
    private const val SIZE = 64
    private const val BORDER = 8
    private val metadata = """{"gui":{"scaling":{"type":"nine_slice","width":64,"height":64,"border":8,"stretch_inner":true}}}
""".toByteArray()

    private fun colours(content: Path): Map<String, Int> {
        var node: Any? = Yaml().load<Any>(Files.readString(content.resolve("languages/en.yaml")))
        for (key in listOf("astera", "item", "tooltip", "rarity")) node = (node as Map<*, *>)[key]
        return Rarity.entries.associate { rarity ->
            val id = rarity.name.lowercase()
            val text = MiniMessage.miniMessage().deserialize(requireNotNull((node as Map<*, *>)[id]).toString())
            val colour = requireNotNull(text.color() ?: text.children().firstNotNullOfOrNull { it.color() }) {
                "rarity $id needs a lore colour"
            }
            id to colour.value()
        }
    }

    fun renderAll(content: Path): Map<String, ByteArray> {
        val manifest = "textures/sources/tooltips.yaml"
        val doc = Yaml().load<Map<String, Any>>(Files.readString(content.resolve(manifest)))
        val source = Sources.parseSources(content, manifest, doc["sources"] as Map<*, *>).getValue("tooltip-v1")
        val cells = source.raw["cells"] as List<*>
        val (cols, rows) = requireNotNull(source.grid)
        val files = sortedMapOf<String, ByteArray>()
        for ((id, rgb) in colours(content)) {
            val at = cells.indexOf(id)
            require(at >= 0) { "tooltip source missing $id" }
            val ref = Sources.Ref(id, source.name, source.file, Sources.Cell(at % cols, at / cols, cols, rows), 8, null)
            val art = PixelConverter.convert(Sources.crop(ref), PixelConverter.Options(size = SIZE, colors = 16, margin = 0, outline = false))
            val frame = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
            val background = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                // A nine-slice edge must be uniform along its stretch axis; retain generated corners.
                val corner = (x < BORDER || x >= SIZE - BORDER) && (y < BORDER || y >= SIZE - BORDER)
                val sx = if (!corner && x in BORDER until SIZE - BORDER) SIZE / 2 else x
                val sy = if (!corner && y in BORDER until SIZE - BORDER) SIZE / 2 else y
                val p = art.getRGB(sx, sy)
                val light = maxOf(p shr 16 and 255, p shr 8 and 255, p and 255)
                val edge = x < BORDER || y < BORDER || x >= SIZE - BORDER || y >= SIZE - BORDER
                // Only the generated metal survives in the frame; the text area remains transparent.
                if (edge && p ushr 24 != 0 && light > 35) {
                    val shade = if (light >= 150) 255 else 128
                    val r = (rgb shr 16 and 255) * shade / 255
                    val g = (rgb shr 8 and 255) * shade / 255
                    val b = (rgb and 255) * shade / 255
                    frame.setRGB(x, y, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                }
                // Keep the generated interior texture, clamped dark for readable lore over a white scene.
                val interior = art.getRGB(BORDER + x * (SIZE - 2 * BORDER) / SIZE, BORDER + y * (SIZE - 2 * BORDER) / SIZE)
                val dark = ((interior and 255) / 3).coerceIn(6, 14)
                background.setRGB(x, y, (245 shl 24) or (dark shl 16) or ((dark + 2) shl 8) or (dark + 8))
            }
            for ((part, image) in listOf("frame" to frame, "background" to background)) {
                files["$DIR/${id}_$part.png"] = ItemArt.png(image)
                files["$DIR/${id}_$part.mcmeta.json"] = metadata
            }
        }
        return files
    }

    /** Both shipped layers stretched with the same nine-slice contract as the client, at two sizes. */
    fun preview(content: Path, out: Path) {
        val files = renderAll(content)
        val sheet = BufferedImage(1200, 1750, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = Color(0xAEB8A4)
        g.fillRect(0, 0, sheet.width, sheet.height)
        colours(content).entries.forEachIndexed { row, (id, rgb) ->
            for ((col, dimensions) in listOf(220 to 100, 340 to 150).withIndex()) {
                val (w, h) = dimensions
                val box = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val b = box.createGraphics()
                for (part in listOf("background", "frame")) {
                    val src = ItemArt.decode(files.getValue("$DIR/${id}_$part.png"))
                    val from = intArrayOf(0, BORDER, SIZE - BORDER, SIZE)
                    val xs = intArrayOf(0, BORDER, w - BORDER, w)
                    val ys = intArrayOf(0, BORDER, h - BORDER, h)
                    for (y in 0..2) for (x in 0..2) b.drawImage(src,
                        xs[x], ys[y], xs[x + 1], ys[y + 1], from[x], from[y], from[x + 1], from[y + 1], null)
                }
                b.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                b.color = Color(rgb)
                b.drawString("${id.uppercase()}  /  Astera Blade", 12, 20)
                b.color = Color.WHITE
                b.drawString("Left click  Quick slash", 12, 39)
                b.color = Color(0xAAAAAA)
                b.drawString("Damage 8   Cooldown 2s", 12, 56)
                b.color = Color(0x55FFFF)
                b.drawString("Mana 12", 12, 73)
                if (col == 1) {
                    b.color = Color(0xFF5555)
                    b.drawString("Requires Norma level 3", 12, 95)
                    b.color = Color(0xFFAA00)
                    b.drawString("Price 24 ore", 12, 115)
                }
                b.dispose()
                g.drawImage(box, if (col == 0) 20 else 490, row * 345 + 20, w * 2, h * 2, null)
            }
        }
        g.dispose()
        Files.createDirectories(out.parent)
        ImageIO.write(sheet, "png", out.toFile())
    }
}
