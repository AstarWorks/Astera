package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.domain.hud.status.CooldownHudGlyphs
import com.astarworks.astera.core.domain.hud.status.NegativeSpace
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudGlyphs
import com.astarworks.astera.core.domain.hud.status.StatusHudGlyphs
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class HudArtTest {

    private val hudDir: Path = Path.of(checkNotNull(System.getProperty("astera.contentDir"))).resolve("hud")

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    private fun pixels(image: BufferedImage): List<Int> =
        (0 until image.height).flatMap { y -> (0 until image.width).map { x -> image.getRGB(x, y) } }

    @Test
    fun `effect art covers all status content and keeps exact pen advances and frame colours`() {
        val atlas = StatusEffectArt.atlas()
        val yaml = org.yaml.snakeyaml.Yaml()
        Files.list(hudDir.parent.resolve("status")).use { paths ->
            paths.filter { it.toString().endsWith(".yaml") }.forEach { path ->
                val status = yaml.load<Map<String, Any>>(Files.readString(path))
                val id = status.getValue("id").toString()
                assertThat(StatusEffectHudGlyphs.IDS).contains(id)
                val beneficial = status["kind"].toString() in setOf("stack_buff", "speed_up", "heal_amp")
                val frame = atlas.getRGB(StatusEffectHudGlyphs.IDS.indexOf(id) * 9, 1)
                assertThat(frame).`as`("$id frame").isEqualTo(if (beneficial) 0xFF56CB86.toInt() else 0xFFE76370.toInt())
            }
        }
        StatusEffectHudGlyphs.ATLAS_ROWS.forEachIndexed { row, chars ->
            chars.forEachIndexed { column, glyph ->
                if (glyph != '\u0000') {
                    var width = 0
                    for (x in 0 until 9) for (y in 0 until 12) {
                        if (atlas.getRGB(column * 9 + x, row * 12 + y) ushr 24 != 0) width = x + 1
                    }
                    assertThat(width + 1).isEqualTo(StatusEffectHudGlyphs.advance(glyph))
                }
            }
        }
        val provider = ObjectMapper().readTree(Files.readString(hudDir.resolve("effects.json")))["providers"].single()
        assertThat(provider["ascent"].asInt()).isEqualTo(StatusEffectHudGlyphs.ASCENT)
        assertThat(StatusEffectHudGlyphs.ASCENT - CooldownHudGlyphs.ASCENT).isGreaterThanOrEqualTo(12)
        val preview = ImageIO.read(hudDir.resolve("../../docs/assets/status-icons-preview.png").normalize().toFile())
        assertThat(pixels(preview)).isEqualTo(pixels(StatusEffectArt.preview()))
    }

    @Test
    fun `committed HUD art is exactly what the generator draws`() {
        for ((relative, expected) in HudArtGenerator.render()) {
            val committed = hudDir.resolve(relative)
            assertThat(committed).`as`("$relative is committed (run :tools:resource-pack:generateHudArt)").exists()
            val actual = Files.readAllBytes(committed)
            if (relative.endsWith(".png")) {
                assertThat(pixels(decode(actual))).`as`(relative).isEqualTo(pixels(decode(expected)))
            } else {
                assertThat(String(actual)).`as`(relative).isEqualTo(String(expected))
            }
        }
    }

    @Test
    fun `every atlas glyph advances the pen exactly as the glyph table says`() {
        val atlas = decode(Files.readAllBytes(hudDir.resolve("status/atlas.png")))
        StatusHudGlyphs.ATLAS_ROWS.forEachIndexed { row, chars ->
            chars.forEachIndexed { column, glyph ->
                if (glyph == StatusHudGlyphs.NONE) return@forEachIndexed
                val visible = visibleWidth(atlas, column * StatusHudGlyphs.CELL_WIDTH, row * StatusHudGlyphs.CELL_HEIGHT)
                // Minecraft's bitmap provider: advance = visible width + 1.
                assertThat(visible + 1).`as`("advance of U+%04X".format(glyph.code)).isEqualTo(StatusHudGlyphs.advance(glyph))
            }
        }
    }

    @Test
    fun `every cooldown glyph advances the pen exactly as the glyph table says`() {
        val atlas = decode(Files.readAllBytes(hudDir.resolve("cooldown/atlas.png")))
        CooldownHudGlyphs.ATLAS_ROWS.forEachIndexed { row, chars ->
            chars.forEachIndexed { column, glyph ->
                if (glyph == CooldownHudGlyphs.NONE) return@forEachIndexed
                var visible = 0
                for (x in 0 until CooldownHudGlyphs.CELL_WIDTH) for (y in 0 until CooldownHudGlyphs.CELL_HEIGHT) {
                    val argb = atlas.getRGB(column * CooldownHudGlyphs.CELL_WIDTH + x, row * CooldownHudGlyphs.CELL_HEIGHT + y)
                    if ((argb ushr 24) != 0) visible = x + 1
                }
                assertThat(visible + 1).`as`("advance of U+%04X".format(glyph.code)).isEqualTo(CooldownHudGlyphs.advance(glyph))
            }
        }
    }

    @Test
    fun `the cooldown font provider sits one row above the status line`() {
        val provider = ObjectMapper().readTree(Files.readAllBytes(hudDir.resolve("cooldown.json"))).path("providers").single()
        assertThat(provider.path("ascent").asInt()).isEqualTo(CooldownHudGlyphs.ASCENT)
        assertThat(provider.path("chars").map { it.asText() }).isEqualTo(CooldownHudGlyphs.ATLAS_ROWS)
    }

    private fun visibleWidth(image: BufferedImage, ox: Int, oy: Int): Int {
        var widest = 0
        for (x in 0 until StatusHudGlyphs.CELL_WIDTH) {
            for (y in 0 until StatusHudGlyphs.CELL_HEIGHT) {
                if ((image.getRGB(ox + x, oy + y) ushr 24) != 0) widest = x + 1
            }
        }
        return widest
    }

    @Test
    fun `the font JSON covers every glyph row and every spacer`() {
        val mapper = ObjectMapper()
        val status = mapper.readTree(Files.readAllBytes(hudDir.resolve("status.json"))).path("providers").single()
        assertThat(status.path("ascent").asInt()).isEqualTo(StatusHudGlyphs.ASCENT)
        assertThat(status.path("height").asInt()).isEqualTo(StatusHudGlyphs.CELL_HEIGHT)
        assertThat(status.path("chars").map { it.asText() }).isEqualTo(StatusHudGlyphs.ATLAS_ROWS)
        status.path("chars").forEach { assertThat(it.asText()).hasSize(StatusHudGlyphs.ATLAS_COLUMNS) }

        val space = mapper.readTree(Files.readAllBytes(hudDir.resolve("space.json"))).path("providers").single()
        assertThat(space.path("type").asText()).isEqualTo("space")
        val advances = space.path("advances").fields().asSequence().associate { it.key.single() to it.value.asInt() }
        assertThat(advances).isEqualTo(NegativeSpace.advances)
    }

    @Test
    fun `no font provider points at a missing PNG`() {
        val mapper = ObjectMapper()
        val fragments = Files.list(hudDir).use { it.filter { f -> f.toString().endsWith(".json") }.toList() }
        assertThat(fragments).isNotEmpty()
        for (fragment in fragments) {
            for (provider in mapper.readTree(Files.readAllBytes(fragment)).path("providers")) {
                if (provider.path("type").asText() != "bitmap") continue
                val file = provider.path("file").asText().substringAfter("astera:hud/")
                assertThat(hudDir.resolve(file)).`as`("${fragment.fileName}: $file").exists()
            }
        }
    }
}
