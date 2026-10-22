package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.domain.skill.mechanic.PATTERN_OVERLAY_HEIGHT
import com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OverlayArtTest {

    private val contentDir: Path = Path.of(checkNotNull(System.getProperty("astera.contentDir")))

    @Test
    fun `overlay font has one centred bitmap glyph per overlay`() {
        val providers = ObjectMapper().readTree(OverlayArtGenerator.fontJson()).path("providers")
        assertThat(providers.size()).isEqualTo(ScreenOverlay.entries.size)
        ScreenOverlay.entries.forEachIndexed { i, overlay ->
            val p = providers[i]
            assertThat(p.path("height").asInt()).isEqualTo(overlay.height)
            assertThat(p.path("ascent").asInt()).isEqualTo(overlay.height / 2 - 3)
            assertThat(p.path("ascent").asInt()).isLessThanOrEqualTo(p.path("height").asInt())
            assertThat(p.path("chars")[0].asText()).isEqualTo(overlay.glyph.toString())
            assertThat(p.path("file").asText()).isEqualTo("astera:hud/overlay/${overlay.yamlName}.png")
        }
        // 4x title scale: even the smallest pattern glyph spans more than a 4K screen height in GUI pixels at scale 3.
        assertThat(PATTERN_OVERLAY_HEIGHT * 4).isGreaterThanOrEqualTo(2160 / 3 * 2)
    }

    @Test
    fun `every texture keeps an alpha column on the right edge so the glyph advances its full width`() {
        for (overlay in ScreenOverlay.entries) {
            val image = OverlayArtGenerator.texture(overlay)
            for (y in 0 until image.height) {
                assertThat(image.getRGB(image.width - 1, y) ushr 24).`as`("${overlay.yamlName} row $y").isGreaterThan(0)
            }
        }
    }

    @Test
    fun `solid is opaque, vignette and radial lines leave the centre clear`() {
        val white = OverlayArtGenerator.texture(ScreenOverlay.WHITE)
        assertThat(white.getRGB(8, 8) ushr 24).isEqualTo(255)
        for (overlay in listOf(ScreenOverlay.VIGNETTE, ScreenOverlay.SPEED_RADIAL, ScreenOverlay.SPEED_DIAG)) {
            val image = OverlayArtGenerator.texture(overlay)
            assertThat(image.getRGB(image.width / 2, image.height / 2) ushr 24).`as`(overlay.yamlName).isLessThan(8)
        }
    }

    @Test
    fun `the built pack ships the overlay font and textures`() {
        val files = ResourcePackBuilder(contentDir).collect()
        assertThat(files).containsKey("assets/astera/font/overlay.json")
        for (overlay in ScreenOverlay.entries) assertThat(files).containsKey("assets/astera/textures/hud/overlay/${overlay.yamlName}.png")
    }
}
