package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.item.Rarity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** UI-13: every [Rarity] tier (the `astera:<rarity>` tooltip_style set by the item materializer) ships both sprites and their nine-slice meta. */
class ShippedTooltipSpritesTest {

    @Test
    fun `every rarity ships a tooltip background and frame with nine-slice meta`() {
        val dir = locateContentRoot().resolve("textures/gui/sprites/tooltip")
        for (rarity in Rarity.entries) {
            val id = rarity.name.lowercase()
            for (part in listOf("background", "frame")) {
                assertThat(Files.isRegularFile(dir.resolve("${id}_$part.png"))).describedAs("${id}_$part.png").isTrue()
                val meta = dir.resolve("${id}_$part.mcmeta.json")
                assertThat(Files.isRegularFile(meta)).describedAs("${id}_$part.mcmeta.json").isTrue()
                assertThat(Files.readString(meta)).contains("nine_slice")
            }
        }
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found")
    }
}
