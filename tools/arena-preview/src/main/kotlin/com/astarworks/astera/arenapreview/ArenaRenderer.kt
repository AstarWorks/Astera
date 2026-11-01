package com.astarworks.astera.arenapreview

import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.lobbygen.LobbyRenderer
import java.awt.Color
import java.awt.Font
import java.awt.Polygon
import java.awt.image.BufferedImage

/** Software voxel render of the actual plan. Top-down labels retain world coordinates; iso shows exposed faces. */
object ArenaRenderer {
    private val background = Color(0x161c30)

    fun top(stage: Stage, plan: ArenaBuildPlan): BufferedImage {
        val scale = 3
        val image = BufferedImage((plan.maxX - plan.minX + 1) * scale + 48, (plan.maxZ - plan.minZ + 1) * scale + 72, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = background; g.fillRect(0, 0, image.width, image.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.color = Color.WHITE; g.drawString("${stage.id.value} / ${stage.layout!!.theme} / north up", 16, 24)
        val tops = HashMap<Pair<Int, Int>, Pair<Int, String>>()
        for ((pos, state) in plan.blocks) {
            val k = pos.x to pos.z
            if (pos.y > (tops[k]?.first ?: Int.MIN_VALUE)) tops[k] = pos.y to state
        }
        for ((xz, top) in tops) {
            val north = tops[xz.first to xz.second - 1]?.first ?: top.first
            g.color = shade(colorOf(top.second), (1.0 + (top.first - north) * .06).coerceIn(.6, 1.3))
            g.fillRect(24 + (xz.first - plan.minX) * scale, 48 + (xz.second - plan.minZ) * scale, scale, scale)
        }
        fun label(x: Int, z: Int, text: String, colour: Color) {
            val px = 24 + (x - plan.minX) * scale; val py = 48 + (z - plan.minZ) * scale
            g.color = Color.BLACK; g.fillOval(px - 4, py - 4, 8, 8)
            g.color = colour; g.fillOval(px - 2, py - 2, 4, 4)
            g.color = Color.BLACK; g.drawString(text, px + 7, py + 1)
            g.color = Color.WHITE; g.drawString(text, px + 6, py)
        }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        stage.layout!!.islands.forEach { label(it.x, it.z, "${it.id} (${it.x},${it.z})", Color.CYAN) }
        stage.layout!!.landmarks.forEach { label(it.x, it.z, "stars", Color.YELLOW) }
        stage.spawnPoints.filter { it.team != null }.groupBy { it.team }.values.forEach { group ->
            val p = group.first().position; label(p.x.toInt(), p.z.toInt(), "spawn ${group.first().team!!.value}", Color.GREEN)
        }
        g.dispose()
        return image
    }

    fun iso(stage: Stage, plan: ArenaBuildPlan): BufferedImage {
        val sx = 3; val sy = 2; val vertical = 4
        val w = plan.maxX - plan.minX + 1; val d = plan.maxZ - plan.minZ + 1
        val image = BufferedImage((w + d) * sx + 48, (w + d) * sy + (plan.maxY - plan.minY + 1) * vertical + 80, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = background; g.fillRect(0, 0, image.width, image.height)
        g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.drawString("${stage.id.value} / isometric / ${plan.blocks.size} blocks", 16, 26)
        fun face(xs: IntArray, ys: IntArray, c: Color) { g.color = c; g.fillPolygon(Polygon(xs, ys, 4)) }
        // +x/+z faces look towards the viewer; x+z order handles occlusion across tall structures.
        val ordered = plan.blocks.entries.sortedWith(compareBy({ it.key.x + it.key.z }, { it.key.y }, { it.key.x }))
        for ((p, state) in ordered) {
            val c = colorOf(state)
            val px = 24 + (p.x - plan.minX - (p.z - plan.minZ) + d) * sx
            val py = 48 + (p.x - plan.minX + p.z - plan.minZ) * sy + (plan.maxY - p.y) * vertical
            if (!plan.blocks.containsKey(ArenaBlockPos(p.x + 1, p.y, p.z)))
                face(intArrayOf(px, px + sx, px + sx, px), intArrayOf(py + 2 * sy, py + sy, py + sy + vertical, py + 2 * sy + vertical), shade(c, .72))
            if (!plan.blocks.containsKey(ArenaBlockPos(p.x, p.y, p.z + 1)))
                face(intArrayOf(px - sx, px, px, px - sx), intArrayOf(py + sy, py + 2 * sy, py + 2 * sy + vertical, py + sy + vertical), shade(c, .52))
            if (!plan.blocks.containsKey(ArenaBlockPos(p.x, p.y + 1, p.z)))
                face(intArrayOf(px, px + sx, px, px - sx), intArrayOf(py, py + sy, py + 2 * sy, py + sy), c)
        }
        g.dispose()
        return image
    }

    /** Cut through the tier-3 sanctum when present, otherwise the first island/spawn: exposes the keep and rock taper. */
    fun section(stage: Stage, plan: ArenaBuildPlan): BufferedImage {
        val z = stage.pillars.firstOrNull { it.tier.ordinalValue == 3 }?.position?.z?.toInt()
            ?: stage.layout!!.islands.firstOrNull()?.z ?: stage.spawnPoints.first { it.team != null }.position.z.toInt()
        val sx = 3; val sy = 4
        val w = plan.maxX - plan.minX + 1; val h = plan.maxY - plan.minY + 1
        val image = BufferedImage(w * sx + 48, h * sy + 72, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(0x8fb4e8); g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK; g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.drawString("${stage.id.value} / section z=$z / west to east", 16, 24)
        for ((p, state) in plan.blocks) {
            if (p.z != z) continue
            g.color = colorOf(state)
            g.fillRect(24 + (p.x - plan.minX) * sx, 48 + (plan.maxY - p.y) * sy, sx, sy)
        }
        g.dispose()
        return image
    }

    /** Fixed west-flank cameras: red base edge toward yellow, comparable across art passes.
     * Voxel DDA renders the decoded import with perspective and depth fog, without a server.
     */
    fun perspective(plan: ArenaBuildPlan, eyeLevel: Boolean): BufferedImage {
        val eye = if (eyeLevel) doubleArrayOf(-52.5, 66.6, -94.5) else doubleArrayOf(-38.5, 100.0, -91.5)
        val target = doubleArrayOf(-99.0, if (eyeLevel) 66.6 else 61.0, -38.0)
        fun normal(v: DoubleArray): DoubleArray {
            val length = kotlin.math.sqrt(v.sumOf { it * it })
            return DoubleArray(3) { v[it] / length }
        }
        val forward = normal(DoubleArray(3) { target[it] - eye[it] })
        val right = normal(doubleArrayOf(-forward[2], 0.0, forward[0]))
        val up = doubleArrayOf(right[1] * forward[2] - right[2] * forward[1],
            right[2] * forward[0] - right[0] * forward[2], right[0] * forward[1] - right[1] * forward[0])
        val image = BufferedImage(800, 520, BufferedImage.TYPE_INT_RGB)
        val sky = Color(0xaecbf2)
        for (py in 0 until image.height) for (px in 0 until image.width) {
            val u = (2.0 * (px + .5) / image.width - 1) * .72 * image.width / image.height
            val v = (1 - 2.0 * (py + .5) / image.height) * .72
            val ray = normal(DoubleArray(3) { forward[it] + right[it] * u + up[it] * v })
            val cell = IntArray(3) { kotlin.math.floor(eye[it]).toInt() }
            val step = IntArray(3) { if (ray[it] > 0) 1 else -1 }
            val delta = DoubleArray(3) { kotlin.math.abs(1 / ray[it]) }
            val next = DoubleArray(3) { ((if (step[it] > 0) cell[it] + 1.0 else cell[it].toDouble()) - eye[it]) / ray[it] }
            var distance = 0.0
            var axis = 1
            var colour = sky
            while (distance < 230 && cell[1] > 16) {
                val state = plan.stateAt(cell[0], cell[1], cell[2])
                if (state != null) {
                    val c = shade(colorOf(state), when (axis) { 0 -> .78; 2 -> .62; else -> 1.0 })
                    val fog = (distance / 280).coerceIn(0.0, .65)
                    colour = Color((c.red * (1 - fog) + sky.red * fog).toInt(),
                        (c.green * (1 - fog) + sky.green * fog).toInt(), (c.blue * (1 - fog) + sky.blue * fog).toInt())
                    break
                }
                axis = next.indices.minBy { next[it] }
                distance = next[axis]; next[axis] += delta[axis]; cell[axis] += step[axis]
            }
            image.setRGB(px, py, colour.rgb)
        }
        val g = image.createGraphics()
        g.color = Color.BLACK; g.fillRect(0, 0, image.width, 28)
        g.color = Color.WHITE; g.drawString("Highmarch west flank / ${if (eyeLevel) "eye level" else "founder region"} / eye=${eye.joinToString()}", 10, 19)
        g.dispose()
        return image
    }

    // The shared lobby palette has no yellow entry: authored yellow teams otherwise render white.
    private fun colorOf(state: String): Color = when (state.substringAfter(':').substringBefore('[')) {
        "yellow_wool", "yellow_concrete", "yellow_banner", "yellow_wall_banner" -> Color(0xf0c932)
        "red_banner", "red_wall_banner" -> Color(0xb02e26)
        "blue_banner", "blue_wall_banner" -> Color(0x3c44aa)
        "cobbled_deepslate", "deepslate_tiles", "deepslate_tile_stairs" -> Color(0x45454e)
        "gravel" -> Color(0x99938a)
        "spruce_leaves" -> Color(0x31583c)
        "dark_oak_leaves" -> Color(0x294e24)
        else -> LobbyRenderer.colorOf(state)
    }

    private fun shade(c: Color, factor: Double): Color = Color(
        (c.red * factor).toInt().coerceIn(0, 255), (c.green * factor).toInt().coerceIn(0, 255), (c.blue * factor).toInt().coerceIn(0, 255),
    )
}
