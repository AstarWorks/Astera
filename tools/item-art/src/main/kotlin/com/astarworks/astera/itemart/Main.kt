package com.astarworks.astera.itemart

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * CLI of `:tools:item-art`.
 *
 *  - `write <contentDir> <previewPng>`  regenerate the committed art + the contact sheet
 *  - `write-sources <contentDir> <source>...` regenerate only texture PNGs from selected item sheets
 *  - `check <contentDir>`               fail when the committed art is stale
 *  - `convert <image> <outDir> [colors]`  convert one raster with every stage dumped (ADR-0238 tuning aid)
 */
public fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "sprite-graph-reference" -> {
            require(args.size == 2) { "usage: sprite-graph-reference <outputDir>" }
            GraphReference.write(Path(args[1]))
        }
        "sprite-preview" -> SpritePreview.run(args.drop(1))
        "write-sprites" -> {
            require(args.size == 2) { "usage: write-sprites <contentDir>" }
            val content = Path(args[1])
            val files = SpriteSources.renderAll(content)
            for ((rel, bytes) in files) {
                val file = content.resolve(rel)
                Files.createDirectories(file.parent)
                if (!Files.exists(file) || !Files.readAllBytes(file).contentEquals(bytes)) Files.write(file, bytes)
            }
            SpriteSources.orphans(content, files.keys).forEach(Files::delete)
            println("sprite art: ${files.size} files under $content")
        }
        "blocks" -> {
            require(args.size == 2) { "usage: blocks <contentDir>" }
            val content = Path(args[1])
            for ((rel, bytes) in BlockArt.renderAll(content)) {
                val file = content.resolve(rel)
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
            println("block art written")
        }
        "write-sources" -> {
            require(args.size >= 3) { "usage: write-sources <contentDir> <source>..." }
            val content = Path(args[1])
            val sources = Sources.load(content)
            val selected = args.drop(2).toSet()
            require(selected.all { name -> sources.values.any { it.source == name } }) { "unknown item source: $selected" }
            val icons = sources.values.filter { it.source in selected }.associate { it.item to Sources.render(it) }
            val dir = content.resolve(ItemArt.TEXTURE_DIR)
            Files.createDirectories(dir)
            for ((id, image) in icons) ImageIO.write(image, "png", dir.resolve("$id.png").toFile())
            println("item art: ${icons.size} icons from $selected")
        }
        "write" -> {
            require(args.size in 3..4) { "usage: write <contentDir> <previewPng> [<iconPreviewPng>]" }
            val content = Path(args[1])
            val n = ItemArt.write(content)
            writePreview(content, Path(args[2]))
            writeSourcesPreview(content, Path(args[2]).resolveSibling("item-art-sources-preview.png"))
            writeSpritePreview(content, Path(args[2]).resolveSibling("sprite-preview.png"))
            writeParticleSkinPreview(content, Path(args[2]).resolveSibling("particle-skin-preview.png"))
            if (args.size > 3) writeIconPreview(content, Path(args[3]))
            println("item art: $n files under $content, preview ${args[2]}")
        }
        "check" -> {
            require(args.size == 2) { "usage: check <contentDir>" }
            val stale = ItemArt.stale(Path(args[1]))
            if (stale.isNotEmpty()) {
                System.err.println("generated item art is out of date: $stale. Run ./gradlew :tools:item-art:run and commit.")
                exitProcess(1)
            }
            println("item art up to date")
        }
        "convert" -> {
            require(args.size in 3..4) { "usage: convert <image> <outDir> [colors]" }
            val out = Path(args[2])
            Files.createDirectories(out)
            val options = PixelConverter.Options(colors = args.getOrNull(3)?.toInt() ?: PixelConverter.Options().colors)
            val st = PixelConverter.stages(ImageIO.read(Path(args[1]).toFile()), options)
            val paletteStrip = BufferedImage(st.palette.size, 1, BufferedImage.TYPE_INT_ARGB)
            st.palette.forEachIndexed { i, c -> paletteStrip.setRGB(i, 0, c) }
            listOf(
                "1_normalized" to st.normalized, "2_smoothed" to st.smoothed, "3_palette" to paletteStrip,
                "4_pixelized" to st.pixelized, "5_cleaned" to st.cleaned, "6_result" to st.result,
            ).forEach { (name, img) -> ImageIO.write(img, "png", out.resolve("$name.png").toFile()) }
            println("convert: ${st.palette.size} colours, stages under $out")
        }
        else -> {
            System.err.println("usage: write <contentDir> <previewPng> | write-sources <contentDir> <source>... | check <contentDir> | convert <image> <outDir> [colors]")
            exitProcess(2)
        }
    }
}

private const val SCALE = 8
private const val COLUMNS = 5
private const val PAD = 12
private const val LABEL_H = 30

/** Contact sheet: every weapon icon as committed (code-drawn or converted) at x8, labelled with the item id. */
internal fun writePreview(contentDir: Path, out: Path) {
    val files = ItemArt.renderAll(contentDir)
    sheet(ItemArt.weapons(contentDir).map { it.id to ItemArt.decode(files.getValue("${ItemArt.TEXTURE_DIR}/${it.id}.png")) }, out)
}

/**
 * Source comparison: per sourced item, the extracted source, the code-drawn icon (when it has one)
 * and the converted result, so a source switch can be judged at a glance. Deleted when nothing is sourced.
 */
internal fun writeSourcesPreview(contentDir: Path, out: Path) {
    val refs = Sources.load(contentDir).values.sortedBy { it.item }
    if (refs.isEmpty()) { Files.deleteIfExists(out); return }
    val weapons = ItemArt.weapons(contentDir).associateBy { it.id }
    val blank = BufferedImage(Sprite.SIZE, Sprite.SIZE, BufferedImage.TYPE_INT_ARGB)
    val tiles = refs.flatMap { ref ->
        val code = weapons[ref.item]?.let { ItemArt.render(it) } ?: Icons.SPECS[ref.item]?.let { Icons.render(ref.item, it) } ?: blank
        listOf("${ref.item} src" to Sources.crop(ref), "code" to code, "converted" to Sources.render(ref))
    }
    sheet(tiles, out, columns = 6, scale = 5)
}

/** Every sprite's frames in a row at x4 on a checkerboard, labelled with the id. Deleted when there are no sprites. */
internal fun writeSpritePreview(contentDir: Path, out: Path) {
    val defs = SpriteSources.load(contentDir)
    if (defs.isEmpty()) { Files.deleteIfExists(out); return }
    val scale = 4
    val labelW = 96
    val maxFrames = defs.maxOf { it.frames }
    val rowH = defs.maxOf { it.size } * scale + PAD
    val w = labelW + maxFrames * (defs.maxOf { it.size } * scale + 4) + PAD
    val h = defs.size * rowH + PAD
    val sheet = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    try {
        g.color = Color(0x1B1B24)
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        defs.forEachIndexed { row, def ->
            val y = PAD / 2 + row * rowH
            g.color = Color(0xE6E6F0)
            g.drawString(def.id, 8, y + def.size * scale / 2)
            SpriteSources.render(def).forEachIndexed { i, frame ->
                val x = labelW + i * (def.size * scale + 4)
                for (cy in 0 until def.size) for (cx in 0 until def.size) {
                    g.color = if ((cx + cy) % 2 == 0) Color(0x2C2C38) else Color(0x24242F)
                    g.fillRect(x + cx * scale, y + cy * scale, scale, scale)
                }
                g.drawImage(frame, x, y, def.size * scale, def.size * scale, null)
            }
        }
    } finally {
        g.dispose()
    }
    Files.createDirectories(out.parent)
    ImageIO.write(sheet, "png", out.toFile())
}

/**
 * Every particle skin's frames in a row at x2 (grey skins twice: raw, and multiplied by an orange server tint, since
 * that is how they render). Deleted when there are no skins.
 */
internal fun writeParticleSkinPreview(contentDir: Path, out: Path) {
    val skins = ParticleSkins.load(contentDir)
    if (skins.isEmpty()) { Files.deleteIfExists(out); return }
    val scale = 2
    val pad = 6
    val labelW = 170
    val cell = skins.maxOf { it.size } * scale + pad
    val maxFrames = skins.maxOf { it.frames }
    val rows = skins.flatMap { s -> if (s.grey) listOf(s to 0, s to 1) else listOf(s to 0) }
    val w = labelW + maxFrames * cell + pad
    val h = rows.size * cell + pad
    val sheet = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    try {
        g.color = Color(0x15151C)
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        val frames = skins.associateWith { ParticleSkins.render(it) }
        rows.forEachIndexed { row, (skin, variant) ->
            val y = pad / 2 + row * cell
            g.color = Color(0xE6E6F0)
            g.drawString("${skin.type} / ${skin.channel}" + if (skin.grey) (if (variant == 0) " (grey)" else " (tint)") else " (baked)", 6, y + 16)
            frames.getValue(skin).forEachIndexed { i, frame ->
                val x = labelW + i * cell
                g.color = if (variant == 0) Color(0x2A2A36) else Color(0x1B1B24)
                g.fillRect(x, y, skin.size * scale, skin.size * scale)
                val shown = if (skin.grey && variant == 1) multiply(frame, 0xFF8A1E) else frame
                g.drawImage(shown, x, y, skin.size * scale, skin.size * scale, null)
            }
        }
    } finally {
        g.dispose()
    }
    Files.createDirectories(out.parent)
    ImageIO.write(sheet, "png", out.toFile())
}

private fun multiply(src: BufferedImage, rgb: Int): BufferedImage {
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
    val tr = rgb shr 16 and 0xFF
    val tg = rgb shr 8 and 0xFF
    val tb = rgb and 0xFF
    for (y in 0 until src.height) for (x in 0 until src.width) {
        val p = src.getRGB(x, y)
        val r = (p shr 16 and 0xFF) * tr / 255
        val gg = (p shr 8 and 0xFF) * tg / 255
        val b = (p and 0xFF) * tb / 255
        out.setRGB(x, y, (p ushr 24 shl 24) or (r shl 16) or (gg shl 8) or b)
    }
    return out
}

/** Contact sheet of the GUI / kit / skill-kind icons. */
internal fun writeIconPreview(contentDir: Path, out: Path) {
    sheet(ItemArt.icons(contentDir).map { (id, spec) -> id to Icons.render(id, spec) }, out, columns = 8, scale = 5)
}

private fun sheet(items: List<Pair<String, BufferedImage>>, out: Path, columns: Int = COLUMNS, scale: Int = SCALE) {
    val cell = Sprite.SIZE * scale
    val rows = (items.size + columns - 1) / columns
    val w = columns * (cell + PAD) + PAD
    val h = rows * (cell + LABEL_H + PAD) + PAD
    val sheet = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    try {
        g.color = Color(0x1B1B24)
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, if (scale >= 8) 15 else 11)
        items.forEachIndexed { i, (id, image) ->
            val x = PAD + (i % columns) * (cell + PAD)
            val y = PAD + (i / columns) * (cell + LABEL_H + PAD)
            for (cy in 0 until Sprite.SIZE) for (cx in 0 until Sprite.SIZE) {
                g.color = if ((cx + cy) % 2 == 0) Color(0x2C2C38) else Color(0x24242F)
                g.fillRect(x + cx * scale, y + cy * scale, scale, scale)
            }
            val fit = minOf(cell.toDouble() / image.width, cell.toDouble() / image.height)
            val dw = (image.width * fit).toInt()
            val dh = (image.height * fit).toInt()
            g.drawImage(image, x + (cell - dw) / 2, y + (cell - dh) / 2, dw, dh, null)
            g.color = Color(0xE6E6F0)
            g.drawString(id, x + 4, y + cell + 20)
        }
    } finally {
        g.dispose()
    }
    Files.createDirectories(out.parent)
    ImageIO.write(sheet, "png", out.toFile())
}
