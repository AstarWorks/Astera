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
 *  - `check <contentDir>`               fail when the committed art is stale
 */
public fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "write" -> {
            require(args.size in 3..4) { "usage: write <contentDir> <previewPng> [<iconPreviewPng>]" }
            val content = Path(args[1])
            val n = ItemArt.write(content)
            writePreview(content, Path(args[2]))
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
        else -> {
            System.err.println("usage: write <contentDir> <previewPng> | check <contentDir>")
            exitProcess(2)
        }
    }
}

private const val SCALE = 8
private const val COLUMNS = 5
private const val PAD = 12
private const val LABEL_H = 30

/** Contact sheet: every generated icon at x8 on a checkerboard, labelled with the item id. */
internal fun writePreview(contentDir: Path, out: Path) {
    sheet(ItemArt.weapons(contentDir).map { it.id to ItemArt.render(it) }, out)
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
            g.drawImage(image, x, y, cell, cell, null)
            g.color = Color(0xE6E6F0)
            g.drawString(id, x + 4, y + cell + 20)
        }
    } finally {
        g.dispose()
    }
    Files.createDirectories(out.parent)
    ImageIO.write(sheet, "png", out.toFile())
}
