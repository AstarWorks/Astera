package com.astarworks.astera.hudpreview

import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.Closeable
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.floor

/** Resource-pack files take precedence over the vanilla client assets, including transparent sprites. */
class PackAssets(pack: Path, vanilla: Path) : Closeable {
    private val packs = listOf(ZipFile(pack.toFile()), ZipFile(vanilla.toFile()))
    private val images = mutableMapOf<String, BufferedImage>()
    fun bytes(path: String): ByteArray = packs.firstNotNullOfOrNull { zip ->
        zip.getEntry(path)?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
    } ?: error("Missing asset: $path")
    fun image(path: String): BufferedImage = images.getOrPut(path) {
        bytes(path).inputStream().use { ImageIO.read(it) } ?: error("Invalid image: $path")
    }
    override fun close() = packs.forEach { it.close() }
}

internal fun assetPath(id: String, folder: String, suffix: String = ""): String {
    val namespace = id.substringBefore(':', "minecraft")
    return "assets/$namespace/" + (if (folder.isEmpty()) "" else "$folder/") + id.substringAfter(':') + suffix
}

/** Advance includes the trailing pixel, but never adds one to a space provider (even negative space). */
data class Glyph(val image: BufferedImage?, val advance: Float, val height: Float = 0f, val ascent: Float = 7f) {
    val width: Float get() = image?.let { it.width * height / it.height } ?: 0f
    fun top(textY: Float): Float = textY + 7f - ascent
}

class PackFont(private val assets: PackAssets) {
    private val fonts = mutableMapOf<String, List<(Int) -> Glyph?>>()
    private val glyphCache = mutableMapOf<Pair<String, Int>, Glyph>()

    fun glyph(font: String, codepoint: Int): Glyph = glyphCache.getOrPut(font to codepoint) {
        load(font, emptySet()).firstNotNullOfOrNull { it(codepoint) }
            ?: error("Missing glyph U+${codepoint.toString(16).uppercase()} in $font")
    }

    private fun load(id: String, parents: Set<String>): List<(Int) -> Glyph?> {
        fonts[id]?.let { return it }
        require(id !in parents) { "Font reference cycle: $parents -> $id" }
        val doc = Json.parseToJsonElement(assets.bytes(assetPath(id, "font", ".json")).decodeToString()).jsonObject
        val providers = mutableListOf<(Int) -> Glyph?>()
        for (element in doc.getValue("providers").jsonArray) {
            val provider = element.jsonObject
            // Vanilla defaults: Force Unicode Font=false, Japanese glyph variants=false.
            if (provider["filter"]?.jsonObject?.any { (_, value) ->
                    value.jsonPrimitive.boolean
                } == true) continue
            val lookup: (Int) -> Glyph? = when (provider.getValue("type").jsonPrimitive.content.substringAfter(':')) {
                "space" -> provider.getValue("advances").jsonObject.map { (char, width) ->
                    char.codePointAt(0) to Glyph(null, width.jsonPrimitive.float)
                }.toMap()::get
                "bitmap" -> bitmap(provider)::get
                "reference" -> {
                    val referenced = load(provider.getValue("id").jsonPrimitive.content, parents + id)
                    val referenceLookup: (Int) -> Glyph? = { codepoint -> referenced.firstNotNullOfOrNull { it(codepoint) } }
                    referenceLookup
                }
                "unihex" -> unihex(provider)
                else -> error("Unsupported font provider in $id: ${provider["type"]}")
            }
            providers += lookup
        }
        fonts[id] = providers
        return providers
    }

    private fun bitmap(provider: JsonObject): Map<Int, Glyph> {
        val atlas = assets.image(assetPath(provider.getValue("file").jsonPrimitive.content, "textures"))
        val rows = provider.getValue("chars").jsonArray.map { it.jsonPrimitive.content.codePoints().toArray() }
        require(rows.isNotEmpty() && rows.first().isNotEmpty() && rows.all { it.size == rows.first().size })
        require(atlas.width % rows.first().size == 0 && atlas.height % rows.size == 0)
        val cellWidth = atlas.width / rows.first().size
        val cellHeight = atlas.height / rows.size
        val height = provider["height"]?.jsonPrimitive?.float ?: 8f
        val ascent = provider.getValue("ascent").jsonPrimitive.float
        require(height > 0 && ascent <= height)
        return buildMap {
            rows.forEachIndexed { row, chars -> chars.forEachIndexed { column, codepoint ->
                if (codepoint != 0) {
                    val cell = atlas.getSubimage(column * cellWidth, row * cellHeight, cellWidth, cellHeight)
                    // Alpha=1 marker pixels used by Astera count, exactly as in the client.
                    val inkWidth = (cellWidth - 1 downTo 0).firstOrNull { x ->
                        (0 until cellHeight).any { y -> cell.getRGB(x, y) ushr 24 != 0 }
                    }?.plus(1) ?: 0
                    val advance = floor(inkWidth * (height / cellHeight) + 0.5f) + 1f
                    put(codepoint, Glyph(cell, advance, height, ascent))
                }
            } }
        }
    }

    private fun unihex(provider: JsonObject): (Int) -> Glyph? {
        // Keep hex strings, rasterizing only requested characters; a full unifont has >100,000 glyphs.
        val hexes = mutableMapOf<Int, String>()
        val archive = assets.bytes(assetPath(provider.getValue("hex_file").jsonPrimitive.content, ""))
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith(".hex")) continue
                for (line in zip.readBytes().decodeToString().lineSequence().filter { it.isNotBlank() }) {
                    hexes[line.substringBefore(':').toInt(16)] = line.substringAfter(':').trim()
                }
            }
        }
        return lookup@ { codepoint ->
            val hex = hexes[codepoint] ?: return@lookup null
            val width = hex.length / 4 // 16 rows, 4 bits per hex digit
            require(width in listOf(8, 16, 24, 32))
            val pixels = BufferedImage(width, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 16) for (x in 0 until width) {
                val nibble = hex[y * width / 4 + x / 4].digitToInt(16)
                if (nibble and (8 shr (x % 4)) != 0) pixels.setRGB(x, y, -1)
            }
            val used = (0 until width).filter { x -> (0 until 16).any { y -> pixels.getRGB(x, y) != 0 } }
            var left = used.firstOrNull() ?: 0
            var right = used.lastOrNull() ?: 0
            provider["size_overrides"]?.jsonArray?.forEach { value ->
                val rule = value.jsonObject
                val from = rule.getValue("from").jsonPrimitive.content.codePointAt(0)
                val to = rule.getValue("to").jsonPrimitive.content.codePointAt(0)
                if (codepoint in from..to) {
                    left = rule.getValue("left").jsonPrimitive.int
                    right = rule.getValue("right").jsonPrimitive.int
                }
            }
            val cropped = pixels.getSubimage(left, 0, right - left + 1, 16)
            Glyph(cropped, (right - left + 1) / 2f + 1f, 8f, 7f)
        }
    }
}
