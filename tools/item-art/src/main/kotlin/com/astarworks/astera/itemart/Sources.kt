package com.astarworks.astera.itemart

import org.yaml.snakeyaml.Yaml
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Raster sources for item art (ADR-0236): `content/textures/sources/items.yaml` names, per item, an image
 * the icon is converted from instead of being drawn by code. Any tool may produce the image (an AI sheet,
 * Aseprite, a hand-drawn PNG); only the committed file matters. Items not listed stay procedural.
 *
 * ```yaml
 * sources:
 *   weapons-v1: { file: sheets/weapons-v1.png, grid: [5, 5], cells: [bastion-greatsword, couture-rapier] }
 *   my-axe:     { file: images/my-axe.png }
 * items:
 *   bastion-greatsword: weapons-v1
 *   crimson-chain-axe: { from: my-axe, colors: 12, model: handheld }
 * ```
 */
internal object Sources {
    const val DIR: String = "textures/sources"
    const val MANIFEST: String = "$DIR/items.yaml"

    /** Where one item's icon comes from. [cell] is null for a single-image source. */
    data class Ref(
        val item: String,
        val source: String,
        val file: Path,
        val cell: Cell?,
        val colors: Int,
        val model: ModelStyle?,
    )

    /** Column / row in a sheet of [cols] x [rows] equal cells. */
    data class Cell(val col: Int, val row: Int, val cols: Int, val rows: Int)

    fun load(contentDir: Path): Map<String, Ref> {
        val manifest = contentDir.resolve(MANIFEST)
        if (!Files.isRegularFile(manifest)) return emptyMap()
        val doc = Yaml().load<Map<String, Any?>>(Files.readString(manifest)).orEmpty()
        val sources = (doc["sources"] as? Map<*, *>).orEmpty()
        val items = (doc["items"] as? Map<*, *>).orEmpty()
        return items.entries.associate { (k, v) ->
            val item = k.toString()
            val opts = v as? Map<*, *>
            val name = (opts?.get("from") ?: v).toString()
            val src = requireNotNull(sources[name] as? Map<*, *>) { "$MANIFEST: item $item names unknown source '$name'" }
            val file = contentDir.resolve(DIR).resolve(requireNotNull(src["file"]) { "$MANIFEST: source $name has no file" }.toString())
            val cell = (src["grid"] as? List<*>)?.let { grid ->
                val cols = (grid[0] as Number).toInt()
                val rows = (grid[1] as Number).toInt()
                val cells = (src["cells"] as? List<*>).orEmpty().map { it?.toString() }
                val at = cells.indexOf(item)
                require(at in 0 until cols * rows) { "$MANIFEST: item $item is not a cell of sheet '$name'" }
                Cell(at % cols, at / cols, cols, rows)
            }
            val colors = (opts?.get("colors") as? Number)?.toInt() ?: (src["colors"] as? Number)?.toInt() ?: DEFAULT_COLORS
            val model = (opts?.get("model") ?: src["model"])?.toString()?.let { ModelStyle.valueOf(it.uppercase()) }
            item to Ref(item, name, file, cell, colors, model)
        }
    }

    private val keyedSheets = HashMap<Path, Pair<PixelConverter.Raster, Pair<IntArray, List<PixelConverter.Blob>>>>()

    /**
     * The pixels of [ref]: the whole image, or the blobs of its sheet cell. Generated sheets rarely keep
     * to the grid, so a cell is every blob (after keying the sheet background) whose centroid lies in it,
     * not the pixels inside its bounds: a blade crossing the line stays whole, a neighbour's tip stays out.
     */
    fun crop(ref: Ref): BufferedImage {
        val image = requireNotNull(ImageIO.read(ref.file.toFile())) { "unreadable source image ${ref.file}" }
        val cell = ref.cell ?: return image
        val (keyed, comps) = keyedSheets.getOrPut(ref.file) {
            val r = PixelConverter.keyBackground(PixelConverter.Raster.of(image))
            r to PixelConverter.components(r)
        }
        val (label, blobs) = comps
        val x0 = keyed.w * cell.col / cell.cols
        val y0 = keyed.h * cell.row / cell.rows
        val x1 = keyed.w * (cell.col + 1) / cell.cols
        val y1 = keyed.h * (cell.row + 1) / cell.rows
        val mine = blobs.map { it.cx >= x0 && it.cx < x1 && it.cy >= y0 && it.cy < y1 }
        val out = PixelConverter.Raster(keyed.w, keyed.h)
        for (i in label.indices) if (label[i] >= 0 && mine[label[i]]) out.px[i] = keyed.px[i]
        require(out.px.any { it ushr 24 != 0 }) { "sheet ${ref.file}: cell of ${ref.item} is empty" }
        return PixelConverter.crop(out).image()
    }

    fun render(ref: Ref): BufferedImage = PixelConverter.convert(crop(ref), PixelConverter.Options(colors = ref.colors))

    private const val DEFAULT_COLORS = 16
}
