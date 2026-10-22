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
            require(args.size == 3) { "usage: write <contentDir> <previewPng>" }
            val content = Path(args[1])
            val n = ItemArt.write(content)
            writePreview(content, Path(args[2]))
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
    val items = ItemArt.weapons(contentDir)
    val cell = Sprite.SIZE * SCALE
    val rows = (items.size + COLUMNS - 1) / COLUMNS
    val w = COLUMNS * (cell + PAD) + PAD
    val h = rows * (cell + LABEL_H + PAD) + PAD
    val sheet = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    try {
        g.color = Color(0x1B1B24)
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        items.forEachIndexed { i, item ->
            val x = PAD + (i % COLUMNS) * (cell + PAD)
            val y = PAD + (i / COLUMNS) * (cell + LABEL_H + PAD)
            for (cy in 0 until Sprite.SIZE) for (cx in 0 until Sprite.SIZE) {
                g.color = if ((cx + cy) % 2 == 0) Color(0x2C2C38) else Color(0x24242F)
                g.fillRect(x + cx * SCALE, y + cy * SCALE, SCALE, SCALE)
            }
            g.drawImage(ItemArt.render(item), x, y, cell, cell, null)
            g.color = Color(0xE6E6F0)
            g.drawString(item.id, x + 4, y + cell + 20)
        }
    } finally {
        g.dispose()
    }
    Files.createDirectories(out.parent)
    ImageIO.write(sheet, "png", out.toFile())
}
