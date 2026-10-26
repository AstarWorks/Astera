package com.astarworks.astera.bots

import com.astarworks.astera.core.domain.stage.ArenaLayout
import kotlin.math.hypot

/** Tiny authored island/bridge graph. Dijkstra selects corridors; packet-based A* still validates every step. */
class ArenaRouteGraph(private val layout: ArenaLayout) {
    data class Point(val x: Double, val y: Double, val z: Double)

    fun route(sx: Double, sz: Double, gx: Double, gz: Double): List<Point> {
        val islands = layout.islands.associateBy { it.id }
        fun nearest(x: Double, z: Double) = layout.islands.minBy { hypot(it.x + .5 - x, it.z + .5 - z) }.id
        val start = nearest(sx, sz); val goal = nearest(gx, gz)
        if (start == goal) return emptyList()
        val distance = mutableMapOf(start to 0.0)
        val parent = mutableMapOf<String, String>()
        val pending = islands.keys.toMutableSet()
        while (pending.isNotEmpty()) {
            val from = pending.minBy { distance[it] ?: Double.POSITIVE_INFINITY }
            if (from !in distance) return emptyList()
            pending.remove(from)
            if (from == goal) break
            for (bridge in layout.bridges) {
                val to = when (from) { bridge.from -> bridge.to; bridge.to -> bridge.from; else -> continue }
                if (to !in pending) continue
                val a = islands.getValue(from); val b = islands.getValue(to)
                val candidate = distance.getValue(from) + hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble())
                if (candidate < (distance[to] ?: Double.POSITIVE_INFINITY)) {
                    distance[to] = candidate; parent[to] = from
                }
            }
        }
        if (goal !in parent) return emptyList()
        val result = mutableListOf<Point>()
        var next = goal
        while (next != start) {
            val island = islands.getValue(next)
            result += Point(island.x + .5, layout.surfaceY + 1.0, island.z + .5)
            next = parent.getValue(next)
        }
        return result.reversed()
    }
}
