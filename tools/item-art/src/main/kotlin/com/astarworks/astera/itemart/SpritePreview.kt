package com.astarworks.astera.itemart

import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/** Small review artifacts, independent of the resource-pack format. */
internal object SpritePreview {
    fun run(args: List<String>) {
        require(args.size >= 3) { "sprite-preview <contentDir> <id|all> <build/output> [--nodes] [--compare <baselineDir>]" }
        val content = Path.of(args[0])
        val out = Path.of(args[2]).toAbsolutePath().normalize()
        require(out.startsWith(Path.of("build").toAbsolutePath().normalize())) { "previews must be under build/" }
        val defs = SpriteSources.load(content).filter { args[1] == "all" || it.id == args[1] }
        require(defs.isNotEmpty()) { "unknown sprite ${args[1]}" }
        val compare = args.indexOf("--compare").takeIf { it >= 0 }?.let { Path.of(args[it + 1]) }
        val metrics = mutableListOf("sprite\tchanged_pixels\ttotal_pixels")
        for (def in defs) {
            val dir = out.resolve(def.id)
            Files.createDirectories(dir)
            val frames = SpriteSources.render(def)
            frames.forEachIndexed { i, image -> ImageIO.write(image, "png", dir.resolve("frame-%03d.png".format(i)).toFile()) }
            contact(frames.mapIndexed { i, f -> "frame $i" to f }, dir.resolve("contact.png"))
            gif(frames, dir.resolve("animation.gif"))
            if ("--nodes" in args && def.origin is SpriteSources.Origin.Graph) {
                val graph = def.origin.graph
                val renderer = graph.renderer(def.size, def.frames)
                for (node in graph.nodes.keys) {
                    val images = mutableListOf<Pair<String, BufferedImage>>()
                    val values = mutableListOf<String>()
                    for (i in 0 until def.frames) {
                        val value = renderer.intermediate(node, i)
                        if (value is SpriteGraph.Image) {
                            val file = dir.resolve("nodes/$node/frame-%03d.png".format(i))
                            Files.createDirectories(file.parent)
                            ImageIO.write(value.buffered(), "png", file.toFile())
                            images += "frame $i" to value.buffered()
                        } else values += "$i: $value"
                    }
                    if (images.isNotEmpty()) contact(images, dir.resolve("nodes/$node/contact.png"))
                    if (values.isNotEmpty()) {
                        Files.createDirectories(dir.resolve("nodes/$node"))
                        Files.writeString(dir.resolve("nodes/$node/values.txt"), values.joinToString("\n", postfix = "\n"))
                    }
                }
            }
            if (compare != null) {
                val tiles = frames.flatMapIndexed { i, f -> listOf(
                    "before $i" to requireNotNull(ImageIO.read(compare.resolve("${def.id}/frame-%03d.png".format(i)).toFile())),
                    "after $i" to f,
                ) }
                contact(tiles, dir.resolve("comparison.png"))
                var changed = 0L; var total = 0L
                tiles.chunked(2).forEach { pair ->
                    val before = pair[0].second; val after = pair[1].second
                    require(before.width == after.width && before.height == after.height) { "comparison dimensions differ for ${def.id}" }
                    for (y in 0 until after.height) for (x in 0 until after.width) {
                        if (before.getRGB(x, y) != after.getRGB(x, y)) changed++
                        total++
                    }
                }
                metrics += "${def.id}\t$changed\t$total"
            }
        }
        if (compare != null) Files.writeString(out.resolve("comparison.tsv"), metrics.joinToString("\n", postfix = "\n"))
        println("sprite preview: ${defs.size} sprites under $out")
    }

    fun contact(tiles: List<Pair<String, BufferedImage>>, out: Path) {
        val cell = 160
        val columns = minOf(8, tiles.size)
        val sheet = BufferedImage(columns * cell, ((tiles.size + columns - 1) / columns) * (cell + 22), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        try {
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            tiles.forEachIndexed { i, (label, f) ->
                val x = i % columns * cell
                val y = i / columns * (cell + 22)
                for (cy in 0 until cell step 8) for (cx in 0 until cell step 8) {
                    g.color = Color(if ((cx / 8 + cy / 8) % 2 == 0) 0x292932 else 0x202028)
                    g.fillRect(x + cx, y + cy, 8, 8)
                }
                g.drawImage(f, x, y, cell, cell, null)
                g.color = Color.WHITE
                g.drawString(label, x + 4, y + cell + 15)
            }
        } finally { g.dispose() }
        Files.createDirectories(out.parent)
        ImageIO.write(sheet, "png", out.toFile())
    }

    private fun gif(frames: List<BufferedImage>, out: Path) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            FileImageOutputStream(out.toFile()).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                frames.forEachIndexed { index, frame ->
                    val metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(frame), null)
                    val root = metadata.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                    val control = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                    control.setAttribute("disposalMethod", "restoreToBackgroundColor")
                    control.setAttribute("delayTime", "5")
                    control.setAttribute("transparentColorFlag", "TRUE")
                    control.setAttribute("transparentColorIndex", "0")
                    if (index == 0) {
                        val extensions = IIOMetadataNode("ApplicationExtensions")
                        val loop = IIOMetadataNode("ApplicationExtension")
                        loop.setAttribute("applicationID", "NETSCAPE")
                        loop.setAttribute("authenticationCode", "2.0")
                        loop.userObject = byteArrayOf(1, 0, 0)
                        extensions.appendChild(loop)
                        root.appendChild(extensions)
                    }
                    metadata.setFromTree("javax_imageio_gif_image_1.0", root)
                    writer.writeToSequence(IIOImage(frame, null, metadata), null)
                }
                writer.endWriteSequence()
            }
        } finally { writer.dispose() }
    }
}
