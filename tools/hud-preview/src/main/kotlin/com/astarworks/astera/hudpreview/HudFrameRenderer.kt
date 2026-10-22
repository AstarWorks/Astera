package com.astarworks.astera.hudpreview

import com.astarworks.astera.core.domain.hud.status.MinecraftTextWidth
import com.astarworks.astera.core.domain.hud.status.NegativeSpace
import com.astarworks.astera.core.domain.hud.status.StatusLineAnchor
import kotlinx.serialization.json.*
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** GUI coordinates are logical pixels; only the final drawing transform applies GUI scale. */
internal class HudFrameRenderer(private val assets: PackAssets) {
    private val text = HudText(PackFont(assets))

    fun render(trace: TraceFrames, tick: Long, player: String, scale: Int, tab: Boolean): BufferedImage {
        require(scale in 2..3) { "GUI scale must be 2 or 3" }
        val image = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color(0x263644)
            g.fillRect(0, 0, image.width, image.height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.scale(scale.toDouble(), scale.toDouble())
            val width = 1920 / scale
            val height = 1080 / scale
            chrome(g, width, height)
            val live = trace.at(tick, player)
            actionBar(g, live, width, height)
            live["SIDEBAR"]?.let { sidebar(g, it.value, width, height) }
            live["SUBTITLE"]?.let { title(g, it, tick, width, height) }
            if (tab) tab(g, live["TAB_LIST_HEADER"]?.value, trace, width)
        } finally { g.dispose() }
        return image
    }

    private fun sprite(g: Graphics2D, name: String, x: Int, y: Int, width: Int, height: Int) {
        g.drawImage(assets.image("assets/minecraft/textures/gui/sprites/hud/$name.png"), x, y, width, height, null)
    }

    private fun chrome(g: Graphics2D, width: Int, height: Int) {
        sprite(g, "hotbar", width / 2 - 91, height - 22, 182, 22)
        sprite(g, "hotbar_selection", width / 2 - 92, height - 23, 24, 23)
        // Trace records HUD surfaces, not the inventory/food packets. Default empty hotbar, full food.
        for (i in 0 until 10) {
            sprite(g, "heart/container", width / 2 - 91 + i * 8, height - 39, 9, 9)
            sprite(g, "heart/full", width / 2 - 91 + i * 8, height - 39, 9, 9)
            sprite(g, "food_empty", width / 2 + 82 - i * 8, height - 39, 9, 9)
            sprite(g, "food_full", width / 2 + 82 - i * 8, height - 39, 9, 9)
        }
    }

    private fun actionBar(g: Graphics2D, live: Map<String, LiveLayout>, width: Int, height: Int) {
        val readout = text.decode(live["ACTION_BAR"]?.value?.get("component"))
        val status = live["STATUS_LINE"]?.value
        val block = text.decode(status?.get("component"))
        val anchor = status?.get("anchorLeftPx")?.jsonPrimitive?.intOrNull
        val line = if (anchor != null) {
            val blockWidth = checkNotNull(status).getValue("component").jsonObject.getValue("widthPx").jsonPrimitive.int
            check(text.width(block) == blockWidth) { "Status width disagrees with pack: trace=$blockWidth, pack=${text.width(block)}" }
            val estimate = MinecraftTextWidth.estimate(text.plain(readout))
            val gap = StatusLineAnchor.gapPx(anchor, blockWidth, estimate)
            readout + text.decode(buildJsonObject {
                put("text", NegativeSpace.spacer(gap))
                put("font", "astera:default")
            }) + block
        } else if (block.isEmpty()) readout else block + (if (readout.isEmpty()) emptyList() else text.parse("  ") + readout)
        // Hud.extractOverlayMessage: translate(center, h-68), text y=-4. Bitmap top adds 7-ascent.
        text.draw(g, line, width / 2 + -text.width(line) / 2, height - 72)
    }

    private fun sidebar(g: Graphics2D, layout: JsonObject, width: Int, height: Int) {
        val title = text.parse(layout.getValue("title").jsonPrimitive.content)
        val lines = layout.getValue("lines").jsonArray.take(15).map { text.decode(it) }
        val boardWidth = (lines + listOf(title)).maxOf { text.width(it) }
        val left = width - boardWidth - 3
        val bottom = height / 2 + lines.size * 9 / 3
        val top = bottom - lines.size * 9
        g.color = Color(0x66000000, true)
        g.fillRect(left - 2, top - 10, boardWidth + 4, 9)
        g.color = Color(0x4C000000, true)
        g.fillRect(left - 2, top - 1, boardWidth + 4, lines.size * 9 + 1)
        text.draw(g, title, left + boardWidth / 2 - text.width(title) / 2, top - 9, false)
        lines.forEachIndexed { index, line -> text.draw(g, line, left, top + index * 9, false) }
    }

    private fun title(g: Graphics2D, live: LiveLayout, tick: Long, width: Int, height: Int) {
        val layout = live.value
        val fadeIn = layout.getValue("fadeInTicks").jsonPrimitive.int
        val stay = layout.getValue("stayTicks").jsonPrimitive.int
        val fadeOut = layout.getValue("fadeOutTicks").jsonPrimitive.int
        val age = tick - live.at
        val alpha = when {
            age < fadeIn -> age.toFloat() / fadeIn
            age < fadeIn + stay -> 1f
            fadeOut > 0 -> (fadeIn + stay + fadeOut - age).toFloat() / fadeOut
            else -> 0f
        }.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        fun draw(node: JsonElement?, scale: Int, y: Int) {
            val line = text.decode(node)
            val child = g.create() as Graphics2D
            try {
                child.composite = AlphaComposite.SrcOver.derive(alpha)
                child.translate(width / 2, height / 2)
                child.scale(scale.toDouble(), scale.toDouble())
                text.draw(child, line, -text.width(line) / 2, y)
            } finally { child.dispose() }
        }
        draw(layout["title"], 4, -10)
        draw(layout["component"], 2, 5)
    }

    private fun tab(g: Graphics2D, layout: JsonObject?, trace: TraceFrames, width: Int) {
        fun split(letters: List<Letter>): List<List<Letter>> {
            if (letters.isEmpty()) return emptyList()
            val result = mutableListOf<MutableList<Letter>>(mutableListOf())
            for (letter in letters) {
                if (letter.codepoint == 10) result.add(mutableListOf()) else result.last().add(letter)
            }
            return result
        }
        val header = split(text.decode(layout?.get("header")))
        val footer = split(text.decode(layout?.get("footer")))
        val names = trace.players.take(80).map { text.parse(it.getValue("name").jsonPrimitive.content) }
        val columns = (names.size + 19) / 20
        val rows = (names.size + columns - 1) / columns
        val cellWidth = (names.maxOfOrNull { text.width(it) } ?: 0) + 13
        val listWidth = columns * cellWidth + (columns - 1) * 5
        val panelWidth = maxOf(listWidth, (header + footer).maxOfOrNull { text.width(it) } ?: 0)
        val left = width / 2 - panelWidth / 2
        var y = 10
        fun centered(line: List<Letter>) {
            text.draw(g, line, width / 2 - text.width(line) / 2, y)
            y += 9
        }
        g.color = Color(0x80000000.toInt(), true)
        g.fillRect(left - 1, y - 1, panelWidth + 2, (header.size + footer.size + rows) * 9 + 4)
        header.forEach { centered(it) }
        if (header.isNotEmpty()) y++
        val listY = y
        names.forEachIndexed { index, name ->
            text.draw(g, name, width / 2 - listWidth / 2 + index / rows * (cellWidth + 5), listY + index % rows * 9)
        }
        y += rows * 9 + 1
        footer.forEach { centered(it) }
    }
}
