package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.domain.hud.status.CooldownHudGlyphs
import com.astarworks.astera.core.domain.hud.status.NegativeSpace
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudGlyphs
import com.astarworks.astera.core.domain.hud.status.StatusHudGlyphs
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Draws the HUD glyph art (ADR-0182) - deterministic, no randomness, no
 * external assets - and the font JSON that maps it to code points.
 *
 * Output (all under `content/hud/`, committed):
 *
 *  - `status/atlas.png`   HP bar parts + digits, 8x9 cells in the grid of [StatusHudGlyphs.ATLAS_ROWS]
 *  - `status.json`        bitmap provider for the atlas
 *  - `effects/atlas.png` + `effects.json`  9x9 effects and 3x5 seconds/stack badges
 *  - `space.json`         `space` provider with the negative-space characters
 *  - `currency/star-icon.png`  the image `star-counter.json` points at
 *
 * Every glyph has an opaque pixel (or an alpha-1 marker) in its last visible
 * column so the pen advance is exactly [StatusHudGlyphs.advance].
 */
public object HudArtGenerator {

    private const val FRAME = 0xFF2A1A1E.toInt()
    private const val EMPTY_FILL = 0xFF4E343B.toInt()
    private const val EMPTY_LOW = 0xFF3C262C.toInt()
    private const val RED_HIGH = 0xFFF26B6B.toInt()
    private const val RED = 0xFFD42A3C.toInt()
    private const val RED_LOW = 0xFF8E1428.toInt()
    private const val CRIT_HIGH = 0xFFFFC27A.toInt()
    private const val CRIT = 0xFFFF6A2E.toInt()
    private const val CRIT_LOW = 0xFFB23414.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BRASS_HIGH = 0xFFFFE08A.toInt()
    private const val BRASS = 0xFFE0A030.toInt()
    private const val BRASS_LOW = 0xFFA06A18.toInt()
    private const val SHADOW = 0xCC000000.toInt()
    private const val MARKER = 0x01000000

    private val digitRows: Map<Char, List<String>> = mapOf(
        '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '/' to listOf("00001", "00010", "00010", "00100", "01000", "01000", "10000"),
    )

    private val bulletRows: List<String> = listOf(
        "..#..",
        ".###.",
        ".###.",
        ".###.",
        "#####",
        "#####",
    )

    private val heartRows: List<String> = listOf(
        ".##.##.",
        "#######",
        "#######",
        ".#####.",
        "..###..",
        "...#...",
    )

    /** Relative path under `content/hud/` to file bytes. */
    public fun render(): Map<String, ByteArray> = sortedMapOf(
        "status/atlas.png" to png(atlas()),
        "status.json" to statusJson().toByteArray(),
        "cooldown/atlas.png" to png(cooldownAtlas()),
        "cooldown.json" to cooldownJson().toByteArray(),
        "effects/atlas.png" to png(StatusEffectArt.atlas()),
        "effects.json" to effectsJson().toByteArray(),
        "space.json" to spaceJson().toByteArray(),
        "currency/star-icon.png" to png(starIcon()),
    ).apply { putAll(OverlayArtGenerator.render()) }

    public fun writeTo(hudDir: Path): List<Path> {
        val preview = hudDir.resolve("../../docs/assets/status-icons-preview.png").normalize()
        Files.createDirectories(preview.parent)
        Files.write(preview, png(StatusEffectArt.preview()))
        return render().map { (relative, bytes) ->
            val target = hudDir.resolve(relative)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
            target
        } + preview
    }

    // ---- atlas ------------------------------------------------------------

    internal fun atlas(): BufferedImage {
        val rows = StatusHudGlyphs.ATLAS_ROWS
        val image = BufferedImage(
            StatusHudGlyphs.CELL_WIDTH * StatusHudGlyphs.ATLAS_COLUMNS,
            StatusHudGlyphs.CELL_HEIGHT * rows.size,
            BufferedImage.TYPE_INT_ARGB,
        )
        rows.forEachIndexed { row, chars ->
            chars.forEachIndexed { column, glyph ->
                if (glyph != StatusHudGlyphs.NONE) {
                    drawGlyph(image, column * StatusHudGlyphs.CELL_WIDTH, row * StatusHudGlyphs.CELL_HEIGHT, glyph)
                }
            }
        }
        return image
    }

    private fun drawGlyph(image: BufferedImage, ox: Int, oy: Int, glyph: Char) {
        fun px(x: Int, y: Int, argb: Int) = image.setRGB(ox + x, oy + y, argb)
        when (glyph) {
            StatusHudGlyphs.CAP_LEFT, StatusHudGlyphs.CAP_RIGHT -> for (y in 0 until StatusHudGlyphs.CELL_HEIGHT) px(0, y, FRAME)
            StatusHudGlyphs.SEG_EMPTY -> segment(::px, 0, critical = false)
            StatusHudGlyphs.SEG_ONE -> segment(::px, 1, critical = false)
            StatusHudGlyphs.SEG_TWO -> segment(::px, 2, critical = false)
            StatusHudGlyphs.SEG_FULL -> segment(::px, 3, critical = false)
            StatusHudGlyphs.SEG_CRIT_ONE -> segment(::px, 1, critical = true)
            StatusHudGlyphs.SEG_CRIT_TWO -> segment(::px, 2, critical = true)
            StatusHudGlyphs.SEG_CRIT_FULL -> segment(::px, 3, critical = true)
            StatusHudGlyphs.HEART -> heart(::px)
            StatusHudGlyphs.AMMO -> bullet(::px)
            StatusHudGlyphs.SLASH -> text('/', ::px)
            else -> text('0' + (glyph - StatusHudGlyphs.DIGIT_ZERO), ::px)
        }
    }

    /** One bar segment: [lit] of its 3 columns in red, the rest dark; frame on the top and bottom rows. */
    private fun segment(px: (Int, Int, Int) -> Unit, lit: Int, critical: Boolean) {
        val high = if (critical) CRIT_HIGH else RED_HIGH
        val mid = if (critical) CRIT else RED
        val low = if (critical) CRIT_LOW else RED_LOW
        for (x in 0 until StatusHudGlyphs.COLUMNS_PER_SEGMENT) {
            val on = x < lit
            px(x, 0, FRAME)
            px(x, StatusHudGlyphs.CELL_HEIGHT - 1, FRAME)
            for (y in 1 until StatusHudGlyphs.CELL_HEIGHT - 1) {
                px(
                    x,
                    y,
                    when {
                        y == 1 -> if (on) high else EMPTY_FILL
                        y == StatusHudGlyphs.CELL_HEIGHT - 2 -> if (on) low else EMPTY_LOW
                        else -> if (on) mid else EMPTY_FILL
                    },
                )
            }
        }
    }

    /** A brass bullet for the ammo readout; the alpha-1 marker pins the cell's last column (advance 8). */
    private fun bullet(px: (Int, Int, Int) -> Unit) {
        bulletRows.forEachIndexed { y, line ->
            line.forEachIndexed { x, c ->
                if (c == '#') px(x + 1, y + 1, if (y <= 1) BRASS_HIGH else if (y >= 4) BRASS_LOW else BRASS)
            }
        }
        px(StatusHudGlyphs.advance(StatusHudGlyphs.AMMO) - 2, StatusHudGlyphs.CELL_HEIGHT - 1, MARKER)
    }

    private fun heart(px: (Int, Int, Int) -> Unit) {
        heartRows.forEachIndexed { y, line ->
            line.forEachIndexed { x, c ->
                if (c == '#') px(x, y + 1, if (y == 1 && x in 1..2) RED_HIGH else if (y >= 3) RED_LOW else RED)
            }
        }
    }

    /** A 5x7 glyph in white with a one-pixel drop shadow; the alpha-1 marker pins the cell's last column. */
    private fun text(char: Char, px: (Int, Int, Int) -> Unit) {
        val rows = digitRows.getValue(char)
        val lit = HashSet<Pair<Int, Int>>()
        rows.forEachIndexed { y, line -> line.forEachIndexed { x, c -> if (c == '1') lit += x to (y + 1) } }
        for ((x, y) in lit) if ((x + 1 to y + 1) !in lit) px(x + 1, y + 1, SHADOW)
        for ((x, y) in lit) px(x, y, WHITE)
        val markerX = StatusHudGlyphs.advance(StatusHudGlyphs.digit(0)) - 2
        px(markerX, StatusHudGlyphs.CELL_HEIGHT - 1, MARKER)
    }


    // ---- cooldown icons (ADR-0197) -------------------------------------------

    private val mouseRows: List<String> = listOf(
        ".######.",
        "#LL##RR#",
        "#LL##RR#",
        "#LL##RR#",
        "########",
        "#......#",
        "#......#",
        "#......#",
        ".######.",
    )

    /** The shift arrow drawn over the mouse's lower half for the sneak variants. */
    private val sneakRows: List<String> = listOf(
        "...SS...",
        "..SSSS..",
        ".SSSSSS.",
    )

    private val keyRows: List<String> = listOf(
        "########",
        "#......#",
        "#......#",
        "#......#",
        "#......#",
        "#......#",
        "#......#",
        "#......#",
        "########",
    )

    private val letterQ: List<String> = listOf("01110", "10001", "10001", "10001", "10101", "10010", "01101")

    private class IconPalette(val outline: Int, val body: Int, val accent: Int)

    private val readyPalette = IconPalette(0xFFEDEDED.toInt(), 0xFF2B2B2B.toInt(), 0xFF58D058.toInt())
    private val coolingPalette = IconPalette(0xFF777777.toInt(), 0xFF1C1C1C.toInt(), 0xFF8C8C8C.toInt())

    internal fun cooldownAtlas(): BufferedImage {
        val image = BufferedImage(
            CooldownHudGlyphs.CELL_WIDTH * CooldownHudGlyphs.ATLAS_COLUMNS,
            CooldownHudGlyphs.CELL_HEIGHT * CooldownHudGlyphs.ATLAS_ROWS.size,
            BufferedImage.TYPE_INT_ARGB,
        )
        CooldownHudGlyphs.ATLAS_ROWS.forEachIndexed { row, chars ->
            chars.forEachIndexed { column, glyph ->
                if (glyph == CooldownHudGlyphs.NONE) return@forEachIndexed
                val ox = column * CooldownHudGlyphs.CELL_WIDTH
                val oy = row * CooldownHudGlyphs.CELL_HEIGHT
                if (CooldownHudGlyphs.isIcon(glyph)) {
                    val trigger = CooldownHudGlyphs.TRIGGERS.first {
                        glyph == CooldownHudGlyphs.ready(it) || glyph == CooldownHudGlyphs.cooling(it)
                    }
                    val palette = if (glyph == CooldownHudGlyphs.ready(trigger)) readyPalette else coolingPalette
                    drawIcon(image, ox, oy, trigger, palette)
                } else {
                    text('0' + (glyph - CooldownHudGlyphs.digit(0))) { x, y, argb -> image.setRGB(ox + x, oy + y, argb) }
                }
            }
        }
        return image
    }

    private fun drawIcon(image: BufferedImage, ox: Int, oy: Int, trigger: SkillTrigger, palette: IconPalette) {
        fun px(x: Int, y: Int, argb: Int) = image.setRGB(ox + x, oy + y, argb)
        if (trigger == SkillTrigger.DROP) {
            keyRows.forEachIndexed { y, line ->
                line.forEachIndexed { x, c -> px(x, y, if (c == '#') palette.outline else palette.body) }
            }
            letterQ.forEachIndexed { y, line -> line.forEachIndexed { x, c -> if (c == '1') px(x + 2, y + 1, palette.accent) } }
            return
        }
        val left = trigger == SkillTrigger.LEFT_CLICK || trigger == SkillTrigger.SNEAK_LEFT
        val sneak = trigger == SkillTrigger.SNEAK_LEFT || trigger == SkillTrigger.SNEAK_RIGHT
        mouseRows.forEachIndexed { y, line ->
            line.forEachIndexed { x, c ->
                when (c) {
                    '#' -> px(x, y, palette.outline)
                    '.' -> px(x, y, palette.body)
                    'L' -> px(x, y, if (left) palette.accent else palette.body)
                    'R' -> px(x, y, if (left) palette.body else palette.accent)
                }
            }
        }
        if (sneak) sneakRows.forEachIndexed { i, line -> line.forEachIndexed { x, c -> if (c == 'S') px(x, 5 + i, palette.accent) } }
    }

    internal fun effectsJson(): String = json(
        linkedMapOf(
            "_comment" to "Status effect icons (ADR-0236). GENERATED by :tools:resource-pack generateHudArt. PUA E300-E33F.",
            "providers" to listOf(
                linkedMapOf(
                    "type" to "bitmap",
                    "file" to "astera:hud/effects/atlas.png",
                    "ascent" to StatusEffectHudGlyphs.ASCENT,
                    "height" to StatusEffectHudGlyphs.CELL_HEIGHT,
                    "chars" to StatusEffectHudGlyphs.ATLAS_ROWS,
                ),
            ),
        ),
    )

    internal fun cooldownJson(): String = json(
        linkedMapOf(
            "_comment" to "Cooldown icon glyphs (ADR-0197). GENERATED by :tools:resource-pack generateHudArt from " +
                "CooldownHudGlyphs - do not edit by hand. PUA range E200-E2FF reserved for the cooldown family.",
            "providers" to listOf(
                linkedMapOf(
                    "type" to "bitmap",
                    "file" to "astera:hud/cooldown/atlas.png",
                    "ascent" to CooldownHudGlyphs.ASCENT,
                    "height" to CooldownHudGlyphs.CELL_HEIGHT,
                    "chars" to CooldownHudGlyphs.ATLAS_ROWS,
                ),
            ),
        ),
    )

    // ---- small icons --------------------------------------------------------

    private fun starIcon(): BufferedImage = shape(
        9,
        10,
        0xFFFFC62E.toInt(),
        0xFFFFF0A0.toInt(),
        listOf(
            "....#....",
            "....#....",
            "...###...",
            "#########",
            ".#######.",
            "..#####..",
            "..#####..",
            ".###.###.",
            ".##...##.",
            "#.......#",
        ),
    )

    private fun shape(width: Int, height: Int, color: Int, highlight: Int, rows: List<String>): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        rows.forEachIndexed { y, line -> line.forEachIndexed { x, c -> if (c == '#') image.setRGB(x, y, if (y < 3) highlight else color) } }
        return image
    }

    // ---- JSON ---------------------------------------------------------------

    private val mapper: JsonMapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()

    private val printer = DefaultPrettyPrinter().also { it.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE) }

    private fun json(value: Any): String {
        val out = java.io.StringWriter()
        val generator: JsonGenerator = mapper.factory.createGenerator(out).setPrettyPrinter(printer)
        mapper.writeValue(generator, value)
        return out.toString().replace("\r\n", "\n") + "\n"
    }

    internal fun statusJson(): String = json(
        linkedMapOf(
            "_comment" to "Status HUD glyphs (ADR-0182). GENERATED by :tools:resource-pack generateHudArt from " +
                "StatusHudGlyphs - do not edit by hand. PUA range E100-E1FF reserved for the status family.",
            "providers" to listOf(
                linkedMapOf(
                    "type" to "bitmap",
                    "file" to "astera:hud/status/atlas.png",
                    "ascent" to StatusHudGlyphs.ASCENT,
                    "height" to StatusHudGlyphs.CELL_HEIGHT,
                    "chars" to StatusHudGlyphs.ATLAS_ROWS,
                ),
            ),
        ),
    )

    internal fun spaceJson(): String = json(
        linkedMapOf(
            "_comment" to "Negative-space characters (ADR-0097 / ADR-0182). GENERATED from NegativeSpace - do not edit. " +
                "PUA range F810-F838.",
            "providers" to listOf(
                linkedMapOf(
                    "type" to "space",
                    "advances" to NegativeSpace.advances.entries.sortedBy { it.key }.associate { it.key.toString() to it.value },
                ),
            ),
        ),
    )

    private fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }
}
