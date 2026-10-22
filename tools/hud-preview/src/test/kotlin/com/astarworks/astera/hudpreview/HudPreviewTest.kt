package com.astarworks.astera.hudpreview

import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class HudPreviewTest {
    @TempDir lateinit var dir: Path

    private fun png(width: Int, height: Int, pixel: (Int, Int) -> Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, pixel(x, y))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun zip(name: String, files: Map<String, ByteArray>): Path = dir.resolve(name).also { path ->
        ZipOutputStream(path.toFile().outputStream()).use { zip ->
            files.forEach { (key, bytes) ->
                zip.putNextEntry(ZipEntry(key))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun assets(extra: Map<String, ByteArray> = emptyMap()): PackAssets {
        val font = """{"providers":[
          {"type":"space","advances":{"\uf830":-1,"\uf831":-2,"\uf832":-4,"\uf833":-8,"\uf834":-16,"\uf835":-32,"\uf836":-64,"\uf837":-128,"\uf838":-256}},
          {"type":"bitmap","file":"astera:hud.png","height":9,"ascent":-26,"chars":["\ue100"]}
        ]}"""
        val vanilla = mutableMapOf(
            "assets/minecraft/font/default.json" to """{"providers":[{"type":"space","advances":{" ":4}},
              {"type":"bitmap","file":"minecraft:font/ascii.png","ascent":7,"chars":["AB"]}]}""".toByteArray(),
            "assets/minecraft/textures/font/ascii.png" to png(16, 8) { _, _ -> -1 },
        )
        for (sprite in listOf("hotbar", "hotbar_selection", "food_empty", "food_full", "heart/container", "heart/full")) {
            vanilla["assets/minecraft/textures/gui/sprites/hud/$sprite.png"] = png(9, 9) { _, _ -> 0xFF334455.toInt() }
        }
        val pack = mapOf(
            "assets/astera/font/default.json" to font.toByteArray(),
            "assets/astera/textures/hud.png" to png(8, 9) { x, y ->
                when {
                    x == 0 && y == 0 -> 0xFFFF0000.toInt()
                    x == 7 && y == 8 -> 0x01FFFFFF // MC counts a nearly transparent marker column.
                    else -> 0
                }
            },
            "assets/minecraft/textures/gui/sprites/hud/heart/container.png" to png(9, 9) { _, _ -> 0 },
            "assets/minecraft/textures/gui/sprites/hud/heart/full.png" to png(9, 9) { _, _ -> 0 },
        ) + extra
        return PackAssets(zip("pack.zip", pack), zip("vanilla.zip", vanilla))
    }

    @Test
    fun `bitmap counts alpha-one markers and applies ascent from text baseline`() {
        assets().use { assets ->
            val glyph = PackFont(assets).glyph("astera:default", 0xE100)
            assertThat(glyph.advance).isEqualTo(9f)
            assertThat(glyph.height).isEqualTo(9f)
            assertThat(glyph.top(540f - 72f)).isEqualTo(540f - 39f)
            assertThat(glyph.width).isEqualTo(8f)
        }
    }

    @Test
    fun `bitmap scales ink width rounds to nearest and adds exactly one trailing pixel`() {
        val font = """{"providers":[{"type":"bitmap","file":"astera:scaled.png","height":5,"ascent":4,"chars":["A\u0000"]}]}"""
        assets(mapOf(
            "assets/astera/font/scaled.json" to font.toByteArray(),
            "assets/astera/textures/scaled.png" to png(16, 8) { x, _ -> if (x == 4) -1 else 0 },
        )).use { assets ->
            val fonts = PackFont(assets)
            assertThat(fonts.glyph("astera:scaled", 'A'.code).advance).isEqualTo(4f) // floor(5 * 5/8 + .5) + 1
            assertThatThrownBy { fonts.glyph("astera:scaled", 0) }.hasMessageContaining("Missing glyph")
        }
    }

    @Test
    fun `space providers preserve negative advances without adding a bitmap gap`() {
        assets().use { assets ->
            val fonts = PackFont(assets)
            assertThat(fonts.glyph("astera:default", 0xF833).advance).isEqualTo(-8f)
            assertThat(fonts.glyph("astera:default", 0xF833).image).isNull()
            val text = HudText(fonts)
            assertThat(text.width(text.decode(buildJsonObject {
                put("font", "astera:default")
                put("text", "\uE100\uF833\uE100")
            }))).isEqualTo(10)
        }
    }

    @Test
    fun `font references respect first provider priority and supplementary codepoints`() {
        assets(mapOf("assets/astera/font/ref.json" to """{"providers":[
          {"type":"space","advances":{"😀":3}},
          {"type":"reference","id":"astera:default"},
          {"type":"space","advances":{"😀":99}}
        ]}""".toByteArray())).use { assets ->
            val fonts = PackFont(assets)
            assertThat(fonts.glyph("astera:ref", 0x1F600).advance).isEqualTo(3f)
            assertThat(fonts.glyph("astera:ref", 0xE100).advance).isEqualTo(9f)
        }
    }

    @Test
    fun `nested MiniMessage colours and bold survive composite inheritance`() {
        assets().use { assets ->
            val text = HudText(PackFont(assets))
            val letters = text.parse("<red>A<green><bold>B</bold></green>A</red>")
            assertThat(letters.map { it.color }).containsExactly(0xFF5555, 0x55FF55, 0xFF5555)
            assertThat(letters.map { it.bold }).containsExactly(false, true, false)
            assertThat(text.width(letters)).isEqualTo(28) // 9 + (9 + bold 1) + 9
        }
    }

    private fun event(region: String, layout: JsonElement, player: String = "p", op: String = "show") = buildJsonObject {
        put("player", player); put("region", region); put("op", op); put("layout", layout)
    }
    private fun component(text: String, font: String? = null) = buildJsonObject {
        put("text", text)
        font?.let { put("font", it); put("widthPx", 9) }
    }
    private fun trace(events: Map<Long, List<JsonElement>>): TraceFrames = TraceFrames(buildJsonObject {
        put("entities", buildJsonArray {
            for ((id, name) in listOf("p" to "preview", "q" to "other")) add(buildJsonObject {
                put("id", id); put("name", name); put("kind", "player")
            })
        })
        put("ticks", buildJsonArray {
            events.forEach { (tick, hud) -> add(buildJsonObject { put("tick", tick); put("hud", JsonArray(hud)) }) }
        })
    })

    @Test
    fun `replay carries state across quiet ticks and clears only the selected player region`() {
        val layout = buildJsonObject { put("component", component("A")) }
        val trace = trace(mapOf(
            1L to listOf(event("ACTION_BAR", layout), event("STATUS_LINE", layout), event("ACTION_BAR", layout, "q")),
            10L to listOf(event("ACTION_BAR", JsonNull, op = "clear")),
            30L to emptyList(),
        ))
        assertThat(trace.player("preview")).isEqualTo("p")
        assertThat(trace.at(8, "p").keys).containsExactlyInAnyOrder("ACTION_BAR", "STATUS_LINE")
        assertThat(trace.at(30, "p").keys).containsExactly("STATUS_LINE")
        assertThat(trace.at(30, "q").keys).containsExactly("ACTION_BAR")
        assertThatThrownBy { trace.player("missing") }.hasMessageContaining("No trace player")
    }

    @Test
    fun `raster lands anchored HP glyph on hidden heart row at both GUI scales`() {
        val status = buildJsonObject { put("component", component("\uE100", "astera:default")); put("anchorLeftPx", -91) }
        val trace = trace(mapOf(0L to listOf(event("STATUS_LINE", status)), 40L to emptyList()))
        assets().use { assets ->
            val renderer = HudFrameRenderer(assets)
            for (scale in 2..3) {
                val frame = renderer.render(trace, 40, "p", scale, false)
                assertThat(frame.width).isEqualTo(1920)
                assertThat(frame.height).isEqualTo(1080)
                val x = (1920 / scale / 2 - 91) * scale
                val y = (1080 / scale - 39) * scale
                assertThat(frame.getRGB(x, y)).isEqualTo(0xFFFF0000.toInt())
                // Outside the glyph, the pack's hidden hearts reveal the neutral background.
                assertThat(frame.getRGB(x + 16 * scale, y)).isEqualTo(0xFF263644.toInt())
            }
        }
    }

    @Test
    fun `title fade expires while optional tab header and footer remain visible`() {
        val title = buildJsonObject {
            put("title", component("A")); put("component", component("B"))
            put("fadeInTicks", 2); put("stayTicks", 4); put("fadeOutTicks", 2)
        }
        val tab = buildJsonObject { put("header", component("A")); put("footer", component("B")) }
        // Fixture names use only characters supplied by its tiny font.
        val source = buildJsonObject {
            put("entities", buildJsonArray { add(buildJsonObject { put("id", "p"); put("name", "A"); put("kind", "player") }) })
            put("ticks", buildJsonArray {
                add(buildJsonObject { put("tick", 0); put("hud", JsonArray(listOf(event("SUBTITLE", title), event("TAB_LIST_HEADER", tab)))) })
                add(buildJsonObject { put("tick", 20); put("hud", JsonArray(emptyList())) })
            })
        }
        assets().use { assets ->
            val renderer = HudFrameRenderer(assets)
            val frames = TraceFrames(source)
            val shown = renderer.render(frames, 3, "p", 2, false)
            val expired = renderer.render(frames, 20, "p", 2, false)
            assertThat(shown.getRGB(928, 460)).isEqualTo(-1) // title: (center-4*4, centerY-10*4) * GUI scale
            assertThat(expired.getRGB(928, 460)).isEqualTo(0xFF263644.toInt())
            val withTab = renderer.render(frames, 20, "p", 2, true)
            assertThat(withTab.getRGB(952, 20)).isEqualTo(-1)
        }
    }

    @Test
    fun `sidebar uses measured width right margin and MiniMessage colours`() {
        val board = buildJsonObject {
            put("title", "<green>A</green>")
            put("lines", JsonArray(listOf(component("<red>B</red>"))))
        }
        assets().use { assets ->
            val frame = HudFrameRenderer(assets).render(trace(mapOf(0L to listOf(event("SIDEBAR", board)))), 0, "p", 2, false)
            // Logical width 960; left=960-9-3, bottom=270+3, body y=273-9.
            assertThat(frame.getRGB(948 * 2, 264 * 2)).isEqualTo(0xFFFF5555.toInt())
            assertThat(frame.getRGB(948 * 2, 255 * 2)).isEqualTo(0xFF55FF55.toInt())
        }
    }

    @Test
    fun `reference cycles fail with a useful diagnostic`() {
        assets(mapOf("assets/astera/font/cycle.json" to
            """{"providers":[{"type":"reference","id":"astera:cycle"}]}""".toByteArray())).use { assets ->
            assertThatThrownBy { PackFont(assets).glyph("astera:cycle", 65) }.hasMessageContaining("Font reference cycle")
        }
    }

    @Test
    fun `unihex bounds override controls half-scale advance`() {
        val hex = "0041:" + "80".repeat(16) + "\n"
        val archive = java.nio.file.Files.readAllBytes(zip("hex.zip", mapOf("test.hex" to hex.toByteArray())))
        assets(mapOf(
            "assets/astera/font/hex.json" to """{"providers":[{"type":"unihex","hex_file":"astera:font/hex.zip",
                "size_overrides":[{"from":"A","to":"A","left":0,"right":7}]}]}""".toByteArray(),
            "assets/astera/font/hex.zip" to archive,
        )).use { assets ->
            val glyph = PackFont(assets).glyph("astera:hex", 'A'.code)
            assertThat(glyph.advance).isEqualTo(5f)
            assertThat(glyph.height).isEqualTo(8f)
            assertThat(glyph.image!!.getRGB(0, 0)).isEqualTo(-1)
        }
    }

    @Test
    fun `tick selection supports sparse and bounded consecutive frames`() {
        assertThat(parseTicks("1,4..6,4", 20)).containsExactly(1L, 4L, 5L, 6L)
        assertThat(parseTicks("last", 20)).containsExactly(20L)
        assertThatThrownBy { parseTicks("21", 20) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parseTicks("6..4", 20) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
