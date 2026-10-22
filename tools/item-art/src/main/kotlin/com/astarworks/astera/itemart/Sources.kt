package com.astarworks.astera.itemart

import org.yaml.snakeyaml.Yaml
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Raster sources for item art (ADR-0238): `content/textures/sources/items.yaml` names, per item, an image
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
        val parsed = parseSources(contentDir, MANIFEST, sources)
        val items = (doc["items"] as? Map<*, *>).orEmpty()
        return items.entries.associate { (k, v) ->
            val item = k.toString()
            val opts = v as? Map<*, *>
            val name = (opts?.get("from") ?: v).toString()
            val source = requireNotNull(parsed[name]) { "$MANIFEST: item $item names unknown source '$name'" }
            val src = source.raw
            val file = source.file
            val cell = source.grid?.let { (cols, rows) ->
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

    /** A background-keyed sheet with its 8-connected blobs, cached per file. */
    internal class Sheet(val keyed: PixelConverter.Raster, val label: IntArray, val blobs: List<PixelConverter.Blob>, val background: Int?)

    private val sheets = HashMap<Path, Sheet>()

    internal fun sheet(file: Path): Sheet = sheets.getOrPut(file) {
        val image = requireNotNull(ImageIO.read(file.toFile())) { "unreadable source image $file" }
        val raw = PixelConverter.Raster.of(image)
        val r = PixelConverter.keyBackground(raw)
        val (label, blobs) = PixelConverter.components(r)
        Sheet(r, label, blobs, PixelConverter.sheetBackground(raw))
    }

    /** The sheet with only the blobs whose centroid lies in [x0, x1) x [y0, y1) kept. */
    internal fun blobsIn(sheet: Sheet, x0: Int, y0: Int, x1: Int, y1: Int): PixelConverter.Raster {
        val keyed = sheet.keyed
        val mine = sheet.blobs.map { it.cx >= x0 && it.cx < x1 && it.cy >= y0 && it.cy < y1 }
        val out = PixelConverter.Raster(keyed.w, keyed.h)
        for (i in sheet.label.indices) if (sheet.label[i] >= 0 && mine[sheet.label[i]]) out.px[i] = keyed.px[i]
        return out
    }

    /**
     * The pixels of [ref]: the whole image, or the blobs of its sheet cell. Generated sheets rarely keep
     * to the grid, so a cell is every blob (after keying the sheet background) whose centroid lies in it,
     * not the pixels inside its bounds: a blade crossing the line stays whole, a neighbour's tip stays out.
     */
    fun crop(ref: Ref): BufferedImage {
        val cell = ref.cell ?: return requireNotNull(ImageIO.read(ref.file.toFile())) { "unreadable source image ${ref.file}" }
        val sheet = sheet(ref.file)
        val w = sheet.keyed.w
        val h = sheet.keyed.h
        val out = blobsIn(sheet, w * cell.col / cell.cols, h * cell.row / cell.rows, w * (cell.col + 1) / cell.cols, h * (cell.row + 1) / cell.rows)
        require(out.px.any { it ushr 24 != 0 }) { "sheet ${ref.file}: cell of ${ref.item} is empty" }
        return PixelConverter.crop(out).image()
    }

    /** `sources:` of a manifest: name to (file, grid cols x rows or null, raw map). */
    internal class Source(val name: String, val file: Path, val grid: Pair<Int, Int>?, val raw: Map<*, *>)

    internal fun parseSources(contentDir: Path, manifest: String, sources: Map<*, *>): Map<String, Source> =
        sources.entries.associate { (k, v) ->
            val name = k.toString()
            val src = requireNotNull(v as? Map<*, *>) { "$manifest: source $name is not a map" }
            val file = contentDir.resolve(DIR).resolve(requireNotNull(src["file"]) { "$manifest: source $name has no file" }.toString())
            val grid = (src["grid"] as? List<*>)?.let { (it[0] as Number).toInt() to (it[1] as Number).toInt() }
            name to Source(name, file, grid, src)
        }

    fun render(ref: Ref): BufferedImage = PixelConverter.convert(crop(ref), PixelConverter.Options(colors = ref.colors))

    private const val DEFAULT_COLORS = 16
}
