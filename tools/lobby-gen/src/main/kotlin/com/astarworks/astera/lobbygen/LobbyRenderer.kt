package com.astarworks.astera.lobbygen

import com.astarworks.astera.core.domain.lobby.LobbyBuildPlan
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Draws a [LobbyBuildPlan] as a top-down map plus a south-looking elevation. */
object LobbyRenderer {

    private const val SCALE = 6
    private const val MARGIN = 24
    private const val HEADER = 34

    fun render(plan: LobbyBuildPlan): BufferedImage {
        val w = plan.maxX - plan.minX + 1
        val d = plan.maxZ - plan.minZ + 1
        val h = plan.maxY - plan.minY + 1
        val top = BufferedImage(w * SCALE, d * SCALE, BufferedImage.TYPE_INT_RGB)
        val elev = BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB)
        paintTop(plan, top)
        paintElevation(plan, elev)

        val width = w * SCALE + MARGIN * 2 + 90
        val height = HEADER + d * SCALE + MARGIN + h * SCALE + MARGIN
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x10, 0x14, 0x24)
        g.fillRect(0, 0, width, height)
        g.color = Color(0xe8, 0xec, 0xf4)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.drawString("Astera sky-island lobby - top-down, north up (${w}x$d blocks, surface y=${plan.surfaceY})", MARGIN, 22)
        g.drawImage(top, MARGIN, HEADER, null)
        g.drawImage(elev, MARGIN, HEADER + d * SCALE + MARGIN, null)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.drawString("elevation from the south (x across, y up)", MARGIN, HEADER + d * SCALE + MARGIN - 6)

        fun px(x: Double) = MARGIN + ((x - plan.minX) * SCALE).toInt()
        fun pz(z: Double) = HEADER + ((z - plan.minZ) * SCALE).toInt()
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
        plan.npcSpots.forEach { (id, spot) ->
            marker(g, px(spot.x), pz(spot.z), Color(0xff, 0xe0, 0x4a), id.removeSuffix("-npc"))
        }
        plan.lanePads.entries.forEachIndexed { i, (lane, pos) ->
            marker(g, px(pos.x), pz(pos.z), Color(0x5a, 0xe0, 0xff), lane, below = true, row = i % 2)
        }
        marker(g, px(plan.spawn.x), pz(plan.spawn.z), Color(0xff, 0x6a, 0x6a), "spawn")
        g.dispose()
        return img
    }

    private fun marker(g: java.awt.Graphics2D, x: Int, y: Int, color: Color, label: String, below: Boolean = false, row: Int = 0) {
        g.color = Color.BLACK
        g.fillOval(x - 6, y - 6, 12, 12)
        g.color = color
        g.fillOval(x - 4, y - 4, 8, 8)
        val lx = if (below) x - g.fontMetrics.stringWidth(label) / 2 else x + 8
        val ly = if (below) y + 22 + row * 13 else y + 4
        g.color = Color.BLACK
        g.drawString(label, lx + 1, ly + 1)
        g.color = Color.WHITE
        g.drawString(label, lx, ly)
    }

    private fun paintTop(plan: LobbyBuildPlan, img: BufferedImage) {
        val w = plan.maxX - plan.minX + 1
        val d = plan.maxZ - plan.minZ + 1
        val topY = Array(w) { IntArray(d) { Int.MIN_VALUE } }
        val topState = Array(w) { arrayOfNulls<String>(d) }
        for ((pos, state) in plan.blocks) {
            val ix = pos.x - plan.minX
            val iz = pos.z - plan.minZ
            if (pos.y > topY[ix][iz]) {
                topY[ix][iz] = pos.y
                topState[ix][iz] = state
            }
        }
        val g = img.createGraphics()
        g.color = Color(0x16, 0x1c, 0x30)
        g.fillRect(0, 0, img.width, img.height)
        for (ix in 0 until w) {
            for (iz in 0 until d) {
                val state = topState[ix][iz] ?: continue
                val y = topY[ix][iz]
                val north = if (iz > 0 && topY[ix][iz - 1] != Int.MIN_VALUE) topY[ix][iz - 1] else y
                val shade = (1.0 + (y - north) * 0.07 + (y - plan.surfaceY) * 0.012).coerceIn(0.55, 1.35)
                g.color = shaded(colorOf(state), shade)
                g.fillRect(ix * SCALE, iz * SCALE, SCALE, SCALE)
            }
        }
        g.dispose()
    }

    private fun paintElevation(plan: LobbyBuildPlan, img: BufferedImage) {
        val w = plan.maxX - plan.minX + 1
        val h = plan.maxY - plan.minY + 1
        val g = img.createGraphics()
        g.color = Color(0x16, 0x1c, 0x30)
        g.fillRect(0, 0, img.width, img.height)
        val nearest = HashMap<Long, Pair<Int, String>>()
        for ((pos, state) in plan.blocks) {
            val key = (pos.x - plan.minX).toLong() * 100_000L + (pos.y - plan.minY)
            val cur = nearest[key]
            if (cur == null || pos.z > cur.first) nearest[key] = pos.z to state
        }
        for ((key, v) in nearest) {
            val ix = (key / 100_000L).toInt()
            val iy = (key % 100_000L).toInt()
            g.color = colorOf(v.second)
            g.fillRect(ix * SCALE, (h - 1 - iy) * SCALE, SCALE, SCALE)
        }
        g.dispose()
        check(w * SCALE == img.width)
    }

    private fun shaded(c: Color, f: Double): Color =
        Color((c.red * f).toInt().coerceIn(0, 255), (c.green * f).toInt().coerceIn(0, 255), (c.blue * f).toInt().coerceIn(0, 255))

    fun colorOf(state: String): Color {
        val name = state.substringAfter(':').substringBefore('[')
        PALETTE[name]?.let { return Color(it) }
        return when {
            name.endsWith("_concrete") -> concrete(name.removeSuffix("_concrete"))
            name.endsWith("_wool") -> concrete(name.removeSuffix("_wool"))
            name.endsWith("_leaves") -> Color(0x2f, 0x7a, 0x22)
            name.endsWith("_log") -> Color(0x6b, 0x4e, 0x2b)
            name.endsWith("_planks") || name.endsWith("_slab") || name.endsWith("_stairs") || name.endsWith("_fence") -> Color(0xa8, 0x81, 0x4f)
            name.endsWith("_wall") || name.contains("stone") -> Color(0x7a, 0x7a, 0x78)
            name.endsWith("_sign") -> Color(0xa8, 0x81, 0x4f)
            else -> Color(0xb0 - (name.hashCode() and 0x3f), 0x90, 0xa0 + (name.hashCode() and 0x1f))
        }
    }

    private fun concrete(color: String): Color = when (color) {
        "lime" -> Color(0x7f, 0xc3, 0x1c)
        "red" -> Color(0xb0, 0x2e, 0x26)
        "light_blue" -> Color(0x3a, 0xaf, 0xd9)
        "blue" -> Color(0x3c, 0x44, 0xaa)
        "gray" -> Color(0x6a, 0x6e, 0x70)
        "light_gray" -> Color(0x9d, 0x9d, 0x97)
        else -> Color(0xe8, 0xe8, 0xe6)
    }

    private val PALETTE: Map<String, Int> = mapOf(
        "snow_block" to 0xe6f1ff, "packed_ice" to 0x90b9ed, "deepslate" to 0x45454e,
        "deepslate_bricks" to 0x51515a, "basalt" to 0x55545b, "blackstone" to 0x302b35,
        "polished_blackstone_bricks" to 0x39313f, "sandstone" to 0xd8c58c, "smooth_sandstone" to 0xe0ce96,
        "cut_sandstone" to 0xd4c18a, "terracotta" to 0x98634d, "glowstone" to 0xe9ce82,
        "crimson_planks" to 0x71374e, "crimson_stem" to 0x572536, "nether_wart_block" to 0x991d2d,
        "shroomlight" to 0xf5aa5c, "grass_block" to 0x5da83c, "dirt" to 0x866043, "coarse_dirt" to 0x77553b, "dirt_path" to 0xa0804a,
        "stone" to 0x7d7d7d, "andesite" to 0x888888, "tuff" to 0x6b6b60, "calcite" to 0xdcdcd6,
        "moss_block" to 0x59702b, "amethyst_block" to 0x8a5fd0, "water" to 0x3a6fd8,
        "stone_bricks" to 0x7a7a78, "polished_andesite" to 0x929292, "smooth_stone" to 0xa8a8a8,
        "chiseled_stone_bricks" to 0x777777, "stone_brick_slab" to 0x7a7a78, "stone_brick_wall" to 0x7a7a78,
        "smooth_quartz" to 0xeae6dd, "quartz_bricks" to 0xe8e2d8, "chiseled_quartz_block" to 0xf0ece4,
        "quartz_slab" to 0xeae6dd, "sea_lantern" to 0xcfe8e0, "lantern" to 0xf5c542,
        "oak_leaves" to 0x2f7a22, "birch_leaves" to 0x6a9a3a, "cherry_leaves" to 0xf0a8c8,
        "azalea_leaves" to 0x4a8a2c, "flowering_azalea_leaves" to 0xc87ad0,
        "cherry_planks" to 0xe3b3a2, "cherry_slab" to 0xe3b3a2, "cherry_log" to 0x6b4a4a, "stripped_cherry_log" to 0xd9a5a0,
        "cherry_stairs" to 0xe3b3a2, "cherry_fence" to 0xe3b3a2,
        "spruce_planks" to 0x6b4e2e, "dark_oak_planks" to 0x42301a, "oak_planks" to 0xa8814f,
        "short_grass" to 0x6aa84f, "tall_grass" to 0x6aa84f, "fern" to 0x5a9a44, "lily_pad" to 0x2a7a2a,
        "dandelion" to 0xf5e040, "poppy" to 0xd02020, "azure_bluet" to 0xe8e8f0, "cornflower" to 0x4a5ad0,
        "allium" to 0xb060e0, "oxeye_daisy" to 0xf0f0f0, "pink_tulip" to 0xf090b0, "red_tulip" to 0xd04040,
        "pink_petals" to 0xf4a8c8, "mossy_cobblestone" to 0x5f7f5a, "bookshelf" to 0x8a6a3a,
        "barrel" to 0x8a6a3a, "smithing_table" to 0x4a4a52, "fletching_table" to 0xb89860, "loom" to 0x9a7a4a,
        "cartography_table" to 0x8a6a3a, "blast_furnace" to 0x5a5a5a,
    )
}
