package com.astarworks.astera.arenapreview

import com.astarworks.astera.bots.ArenaRouteGraph
import com.astarworks.astera.bots.PathFinder
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.stage.ArenaIslandKind
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.Stage
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Offline bot traversal check over the generated block model (same non-air solidity as packet Terrain, same
 * [PathFinder], same island route graph as Navigator). For every team spawn it walks to the enemy objective
 * (StageObjectives.objectiveFor, so core/flag/bed/wool exactly as the bot picks it), the centre island, every star
 * pedestal and the top of every castle tower. A failure names the stage, the route and the last reachable cell.
 */
object TraversalCheck {
    private const val MAX_REPLANS = 12
    private const val SPRINT = 5.6
    /** carry-tnt: one piece slows the carrier to 75 percent. */
    private const val CARRIER = 0.75

    private class Walk(val reached: Boolean, val last: Triple<Int, Int, Int>, val nodes: Int, val blocks: Int = 0)

    private class Leg(val x: Double, val z: Double, val y: Double?)

    fun run(stage: Stage, plan: ArenaBuildPlan, stars: List<Pair<Double, Double>>, report: StringBuilder): List<String> {
        val layout = stage.layout ?: return emptyList()
        val spawns = stage.spawnPoints.filter { it.team != null && it.role != SpawnRole.SPECTATOR }
        if (spawns.isEmpty()) return emptyList()
        val finder = PathFinder { x, y, z -> plan.stateAt(x, y, z) != null }
        val graph = ArenaRouteGraph(layout)
        val objectives = StageObjectives.of(stage, stars)
        val centre = layout.islands.firstOrNull { it.kind == ArenaIslandKind.CENTER }
        val problems = mutableListOf<String>()

        fun walk(sx: Int, sy: Int, sz: Int, gx: Double, gz: Double, gy: Double?, arrive: Double): Walk {
            var x = sx; var y = sy; var z = sz; var nodes = 0; var blocks = 0
            val far = hypot(gx - sx, gz - sz) > 48.0
            val legs = (if (far) graph.route(sx + .5, sz + .5, gx, gz).map { Leg(it.x, it.z, it.y) } else emptyList()) + Leg(gx, gz, gy)
            for ((i, leg) in legs.withIndex()) {
                val legArrive = if (i == legs.lastIndex) arrive else 2.0
                var replans = 0
                while (true) {
                    val r = finder.find(x, y, z, leg.x, leg.z, leg.y, legArrive) ?: return Walk(false, Triple(x, y, z), nodes)
                    nodes += r.expanded; blocks += r.cells.size
                    val end = r.cells.last()
                    val progressed = end.x != x || end.y != y || end.z != z
                    x = end.x; y = end.y; z = end.z
                    if (r.complete) break
                    if (!progressed || ++replans > MAX_REPLANS) return Walk(false, Triple(x, y, z), nodes, blocks)
                }
            }
            return Walk(true, Triple(x, y, z), nodes, blocks)
        }

        fun check(label: String, sx: Double, sy: Double, sz: Double, gx: Double, gz: Double, gy: Double?, arrive: Double, start: Triple<Int, Int, Int>? = null) {
            val s = start ?: Triple(floor(sx).toInt(), floor(sy).toInt(), floor(sz).toInt())
            val w = walk(s.first, s.second, s.third, gx, gz, gy, arrive)
            report.append("| ${stage.id.value} | $label | ${w.reached} | ${w.nodes} | ${w.last.first},${w.last.second},${w.last.third} | ${w.blocks} | %.1f | %.1f |\n".format(w.blocks / SPRINT, w.blocks / (SPRINT * CARRIER)))
            if (!w.reached) problems += "${stage.id.value}: bot route '$label' from (%.1f,%.1f,%.1f) to (%.1f,%.1f) FAILED; last reachable cell (%d,%d,%d), %.1f blocks short".format(
                sx, sy, sz, gx, gz, w.last.first, w.last.second, w.last.third, hypot(gx - (w.last.first + .5), gz - (w.last.third + .5)),
            )
        }

        for (team in spawns.groupBy { it.team!!.value }.values) {
            val name = team.first().team!!.value
            for ((i, sp) in team.withIndex()) {
                val p = sp.position
                val o = objectives.objectiveFor(p.x, p.z)
                if (o == null) problems += "${stage.id.value}: no enemy objective resolved for $name spawn#$i"
                else check("$name spawn#$i -> enemy objective ${o.name}", p.x, p.y, p.z, o.x + .5, o.z + .5, null, 3.5)
                if (centre != null) check("$name spawn#$i -> centre", p.x, p.y, p.z, centre.x + .5, centre.z + .5, null, 3.0)
            }
            val p = team.first().position
            spawns.firstOrNull { it.team!!.value != name }?.let { e -> check("$name spawn -> enemy spawn", p.x, p.y, p.z, e.position.x, e.position.z, null, 3.0) }
            for ((i, star) in stars.withIndex()) check("$name spawn -> star#$i (${star.first.toInt()},${star.second.toInt()})", p.x, p.y, p.z, star.first + .5, star.second + .5, null, 3.0)
            for ((i, placement) in layout.structures.withIndex()) {
                if (placement.part != "castle-tower") continue
                val foot = placement.transform(ArenaBlockPos(7, 1, 0), layout.surfaceY)
                val top = placement.transform(ArenaBlockPos(0, 17, 0), layout.surfaceY)
                // The tower is a landmark: first reach its foot from the spawn, then climb the stairs from the foot cell.
                check("$name spawn -> tower#$i foot (${foot.x},${foot.z})", p.x, p.y, p.z, foot.x + .5, foot.z + .5, null, 2.0)
                check("$name tower#$i foot -> top (${top.x},${top.y},${top.z})", p.x, p.y, p.z, top.x + .5, top.z + .5, top.y.toDouble(), 2.0, Triple(foot.x, foot.y, foot.z))
            }
        }
        return problems
    }
}
