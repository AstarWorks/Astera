package com.astarworks.astera.bots

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A* over the walkable cells a bot knows from its [Terrain] (ADR-0247). A cell is "feet at block (x, y, z)": a solid
 * block below and two free blocks (feet + head) at and above it. Moves:
 *
 *  - 8 horizontal directions (diagonals only when both side columns are clear, so corners are never cut),
 *  - step up 1 (needs headroom at the start), drop up to [MAX_DROP],
 *  - jump a 1-block gap (cardinal only, landing level or one lower),
 *  - cells next to a void/edge cost extra, so paths keep to the middle of bridges and islands and never graze the rim.
 *
 * The search is capped at [cap] expanded nodes. When the goal is not reached within the cap (or is not connected)
 * the path to the closest expanded node is returned with `complete = false`, and the caller re-plans from there.
 * Unknown chunks are never walkable.
 */
class PathFinder(private val terrain: Terrain) {

    data class Cell(val x: Int, val y: Int, val z: Int, val jump: Boolean)

    class Result(val cells: List<Cell>, val complete: Boolean, val expanded: Int)

    private class Node(val x: Int, val y: Int, val z: Int, val g: Double, val f: Double, val parent: Node?, val jump: Boolean) {
        var closed = false
        var dead = false
    }

    private val standCache = HashMap<Long, Boolean>()

    fun clearCache() = standCache.clear()

    private fun solid(x: Int, y: Int, z: Int): Boolean? = terrain.isSolid(x, y, z)
    private fun air(x: Int, y: Int, z: Int): Boolean = solid(x, y, z) == false

    /** Feet cell with solid ground below and head room. Public: the follower re-validates the next cell each tick. */
    fun standable(x: Int, y: Int, z: Int): Boolean = standCache.getOrPut(key(x, y, z)) {
        solid(x, y - 1, z) == true && air(x, y, z) && air(x, y + 1, z)
    }

    /** The standable cell under / around the foot position, or null when the bot hovers over unknown/void. */
    fun cellAt(px: Double, py: Double, pz: Double): IntArray? {
        val bx = Math.floor(px).toInt()
        val bz = Math.floor(pz).toInt()
        val by = Math.floor(py + 0.05).toInt()
        for (dy in intArrayOf(0, -1, 1, -2, -3)) if (standable(bx, by + dy, bz)) return intArrayOf(bx, by + dy, bz)
        return null
    }

    fun find(
        sx: Int, sy: Int, sz: Int,
        gx: Double, gz: Double, gy: Double?, arrive: Double,
        blocked: Set<Long> = emptySet(), cap: Int = DEFAULT_CAP,
    ): Result? {
        standCache.clear()
        val open = PriorityQueue<Node>(256) { a, b -> a.f.compareTo(b.f) }
        val best = HashMap<Long, Node>(1024)

        fun h(x: Int, z: Int): Double {
            val dx = abs(x + 0.5 - gx)
            val dz = abs(z + 0.5 - gz)
            val d = max(dx, dz) + (SQRT2 - 1.0) * min(dx, dz)
            return max(0.0, d - arrive)
        }
        fun isGoal(n: Node): Boolean =
            hypot(n.x + 0.5 - gx, n.z + 0.5 - gz) <= arrive && (gy == null || abs(n.y - gy) <= 2.5)

        val start = Node(sx, sy, sz, 0.0, h(sx, sz) * WEIGHT, null, false)
        open.add(start)
        best[key(sx, sy, sz)] = start
        val startH = h(sx, sz)
        var bestNode = start
        var bestH = startH
        var expanded = 0

        fun relax(from: Node, x: Int, y: Int, z: Int, cost: Double, jump: Boolean) {
            val k = key(x, y, z)
            if (k in blocked) return
            val g = from.g + cost + edgePenalty(x, y, z)
            val old = best[k]
            if (old != null && old.g <= g) return
            old?.dead = true
            val n = Node(x, y, z, g, g + h(x, z) * WEIGHT, from, jump)
            best[k] = n
            open.add(n)
        }

        while (open.isNotEmpty() && expanded < cap) {
            val n = open.poll()
            if (n.closed || n.dead) continue
            n.closed = true
            expanded++
            if (isGoal(n)) return build(n, true, expanded)
            val hh = h(n.x, n.z)
            if (hh < bestH - 1e-9) { bestH = hh; bestNode = n }
            expand(n, ::relax)
        }
        return if (bestNode !== start && bestH < startH - 1.0) build(bestNode, false, expanded) else null
    }

    private fun expand(n: Node, relax: (Node, Int, Int, Int, Double, Boolean) -> Unit) {
        val x = n.x; val y = n.y; val z = n.z
        for (d in 0 until 8) {
            val dx = DX[d]; val dz = DZ[d]
            val diagonal = dx != 0 && dz != 0
            val dyc = stepDelta(x, y, z, dx, dz)
            if (dyc != null) {
                if (diagonal) {
                    // both side columns must be walk-through at feet and head (and above the head when stepping up)
                    val top = if (dyc > 0) 2 else 1
                    var clear = true
                    for (h in 0..top) if (!air(x + dx, y + h, z) || !air(x, y + h, z + dz)) { clear = false; break }
                    if (!clear) continue
                }
                val base = if (diagonal) SQRT2 else 1.0
                val extra = when {
                    dyc > 0 -> 0.6
                    dyc < 0 -> 0.4 * -dyc
                    else -> 0.0
                }
                relax(n, x + dx, y + dyc, z + dz, base + extra, false)
            } else if (!diagonal) {
                // gap jump: the neighbour column is open air with no floor, the one behind it is ground
                val tx = x + dx; val tz = z + dz
                if (air(tx, y, tz) && air(tx, y + 1, tz) && air(tx, y + 2, tz) && solid(tx, y - 1, tz) == false) {
                    val tx2 = tx + dx; val tz2 = tz + dz
                    for (dyc2 in JUMP_LAND) {
                        val ty = y + dyc2
                        if (!standable(tx2, ty, tz2)) continue
                        if (!air(tx2, y, tz2) || !air(tx2, y + 1, tz2)) continue
                        relax(n, tx2, ty, tz2, JUMP_COST + 0.4 * -dyc2, true)
                        break
                    }
                }
            }
        }
    }

    /** dy of the walkable cell one step in (dx, dz), or null when there is none. */
    private fun stepDelta(x: Int, y: Int, z: Int, dx: Int, dz: Int): Int? {
        val tx = x + dx; val tz = z + dz
        for (dyc in STEP_ORDER) {
            val ty = y + dyc
            if (!standable(tx, ty, tz)) continue
            if (dyc > 0 && !air(x, y + 2, z)) continue
            if (dyc < 0 && !(air(tx, y, tz) && air(tx, y + 1, tz))) continue
            return dyc
        }
        return null
    }

    /** Extra cost for standing next to a drop, so routes keep off the rim. */
    private fun edgePenalty(x: Int, y: Int, z: Int): Double {
        var p = 0.0
        for (d in 0 until 4) {
            val nx = x + DX4[d]; val nz = z + DZ4[d]
            if (solid(nx, y - 1, nz) != true && solid(nx, y - 2, nz) != true && solid(nx, y - 3, nz) != true) p += 0.7
        }
        return min(p, 1.4)
    }

    private fun build(end: Node, complete: Boolean, expanded: Int): Result {
        val out = ArrayList<Cell>()
        var n: Node? = end
        while (n != null) { out += Cell(n.x, n.y, n.z, n.jump); n = n.parent }
        out.reverse()
        return Result(out, complete, expanded)
    }

    companion object {
        const val DEFAULT_CAP = 5_000
        const val MAX_DROP = 3
        private const val WEIGHT = 1.15
        private const val JUMP_COST = 3.5
        private val SQRT2 = Math.sqrt(2.0)
        private val STEP_ORDER = intArrayOf(0, 1, -1, -2, -3)
        private val JUMP_LAND = intArrayOf(0, -1)
        private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DZ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        private val DX4 = intArrayOf(1, -1, 0, 0)
        private val DZ4 = intArrayOf(0, 0, 1, -1)

        fun key(x: Int, y: Int, z: Int): Long =
            (x.toLong() shl 44) xor ((y.toLong() and 0xFFF) shl 32) xor (z.toLong() and 0xFFFFFFFFL)
    }
}
