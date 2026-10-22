package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.domain.hud.status.NegativeSpace
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
