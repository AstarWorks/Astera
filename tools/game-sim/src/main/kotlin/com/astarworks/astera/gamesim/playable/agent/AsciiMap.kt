package com.astarworks.astera.gamesim.playable.agent

import kotlin.math.floor

/** One glyph on the map; the higher [priority] wins a cell. */
data class MapMark(val x: Double, val z: Double, val ch: Char, val priority: Int)

/** A rendered map: [rows] top row = smallest z; cell (c, r) covers x in [originX + c*scale, +scale) and z in [originZ + r*scale, +scale). */
data class MapRender(val rows: List<String>, val originX: Int, val originZ: Int, val scale: Int) {
    val width: Int get() = rows.maxOfOrNull { it.length } ?: 0
    val height: Int get() = rows.size
    /** The window of +-[radius] chars around the first [mark] (the whole map when absent); the origin follows the crop. */
    fun around(mark: Char, radius: Int): MapRender {
        val r = rows.indexOfFirst { mark in it }
        if (r < 0) return this
        val c = rows[r].indexOf(mark)
        val r0 = (r - radius).coerceAtLeast(0)
        val r1 = (r + radius).coerceAtMost(rows.lastIndex)
        val c0 = (c - radius).coerceAtLeast(0)
        val cut = rows.subList(r0, r1 + 1).map { it.drop(c0).take(2 * radius + 1).trimEnd() }
        return MapRender(cut, originX + c0 * scale, originZ + r0 * scale, scale)
    }

    fun text(): String = rows.joinToString("\n")
}

/**
 * Top-down ASCII view of the stage (ADR-0316): 1 char = [scale] x [scale] blocks, `.` = ground, space = void, then the marks
 * (`@` me, lowercase ally, uppercase enemy, `#` pillar up, `x` pillar down, `o` ore, `$` shop, `*` star). Empty border rows and
 * columns are trimmed so the line count follows the island, not the plan bounds.
 */
class AsciiMap(
    private val minX: Int,
    private val maxX: Int,
    private val minZ: Int,
    private val maxZ: Int,
    private val ground: (x: Int, z: Int) -> Boolean,
) {
    fun render(scale: Int, marks: List<MapMark>): MapRender {
        require(scale in 1..32) { "scale must be 1..32" }
        val x0 = Math.floorDiv(minX, scale) * scale
        val z0 = Math.floorDiv(minZ, scale) * scale
        val cols = (maxX - x0) / scale + 1
        val rows = (maxZ - z0) / scale + 1
        val grid = Array(rows) { CharArray(cols) { ' ' } }
        for (r in 0 until rows) for (c in 0 until cols) {
            if (cellHasGround(x0 + c * scale, z0 + r * scale, scale)) grid[r][c] = '.'
        }
        val prio = Array(rows) { IntArray(cols) { Int.MIN_VALUE } }
        for (m in marks) {
            val c = Math.floorDiv(floor(m.x).toInt() - x0, scale)
            val r = Math.floorDiv(floor(m.z).toInt() - z0, scale)
            if (r !in 0 until rows || c !in 0 until cols) continue
            if (m.priority > prio[r][c]) {
                prio[r][c] = m.priority
                grid[r][c] = m.ch
            }
        }
        val lines = grid.map { String(it).trimEnd() }
        val firstRow = lines.indexOfFirst { it.isNotEmpty() }.coerceAtLeast(0)
        val lastRow = lines.indexOfLast { it.isNotEmpty() }.coerceAtLeast(firstRow)
        val kept = lines.subList(firstRow, lastRow + 1)
        val firstCol = kept.minOfOrNull { l -> l.indexOfFirst { it != ' ' }.let { if (it < 0) Int.MAX_VALUE else it } }?.takeIf { it != Int.MAX_VALUE } ?: 0
        return MapRender(kept.map { it.drop(firstCol) }, x0 + firstCol * scale, z0 + firstRow * scale, scale)
    }

    private fun cellHasGround(x: Int, z: Int, scale: Int): Boolean {
        // Sample the cell centre plus corners: cheap and good enough at 4 blocks per char.
        val h = scale / 2
        return ground(x + h, z + h) || ground(x, z) || ground(x + scale - 1, z) || ground(x, z + scale - 1) || ground(x + scale - 1, z + scale - 1)
    }
}
