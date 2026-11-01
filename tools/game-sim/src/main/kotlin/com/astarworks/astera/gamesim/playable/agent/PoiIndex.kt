package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.bots.StageObjectives
import kotlin.math.hypot

/** A named place the agent can `goto` / `dig`. Coordinates are stage-local block coordinates (feet position). */
data class Poi(val name: String, val kind: String, val x: Double, val y: Double, val z: Double, val ore: String = "") {
    fun distanceTo(px: Double, pz: Double): Double = hypot(x - px, z - pz)
}

/**
 * Names for the stage's points of interest (ADR-0316), built from the same content the bots plan with:
 * `StageObjectives` pillars / shop / generators / Mid ore nodes / regions, plus each team's base centre.
 * Names: `pillar:<team>:<tier>`, `shop:<team>`, `gen:<team>:<ore>-<n>`, `mid-ore:<ore>-<n>`, `base:<team>`, region names as authored.
 */
class PoiIndex private constructor(private val all: List<Poi>) {
    val pois: List<Poi> get() = all
    private val byName = all.associateBy { it.name }

    fun find(name: String): Poi? = byName[name]

    fun ofKind(kind: String): List<Poi> = all.filter { it.kind == kind }

    fun nearest(kind: String, x: Double, z: Double): Poi? = ofKind(kind).minByOrNull { it.distanceTo(x, z) }

    /**
     * Resolves a `goto` / `dig` argument: an exact POI name, `x,y,z` / `x,z` numbers, a kind alias relative to [myTeam]
     * (`shop`, `base`) or the nearest of a kind (`mid-ore:diamond`, `pillar`, `gen`).
     */
    fun resolve(arg: String, myTeam: String, fromX: Double, fromZ: Double): Poi? {
        val text = arg.trim()
        byName[text]?.let { return it }
        val nums = text.split(',', ' ').filter { it.isNotBlank() }.mapNotNull { it.toDoubleOrNull() }
        if (nums.size == 3 && text.any { it.isDigit() }) return Poi("xyz", "xyz", nums[0], nums[1], nums[2])
        if (nums.size == 2 && text.any { it.isDigit() }) return Poi("xz", "xyz", nums[0], Double.NaN, nums[1])
        when (text) {
            "shop", "my-shop" -> return byName["shop:$myTeam"]
            "base", "home" -> return byName["base:$myTeam"]
        }
        val prefixed = all.filter { it.name.startsWith("$text:") || it.name.startsWith("$text-") || it.name.startsWith(text) }
        return prefixed.minByOrNull { it.distanceTo(fromX, fromZ) }
    }

    companion object {
        fun build(objectives: StageObjectives, baseCentres: Map<String, Triple<Double, Double, Double>>): PoiIndex {
            val pois = ArrayList<Poi>()
            val seen = HashSet<String>()
            fun add(p: Poi) { if (seen.add(p.name)) pois += p }

            val zones = objectives.zones()
            val pillarCount = HashMap<String, Int>()
            for (p in zones.pillars()) {
                val base = "pillar:${p.team}:${p.tier}"
                val n = pillarCount.merge(base, 1, Int::plus)!!
                add(Poi(if (n == 1) base else "$base-$n", "pillar", p.x + 0.5, p.y.toDouble(), p.z + 0.5))
            }
            val midCount = HashMap<String, Int>()
            for (team in objectives.teams()) {
                baseCentres[team]?.let { (x, y, z) -> add(Poi("base:$team", "base", x, y, z)) }
                val sites = objectives.economySites(team) ?: continue
                sites.shop?.let { add(Poi("shop:$team", "shop", it.x, it.y, it.z)) }
                val genCount = HashMap<String, Int>()
                for (g in sites.generators) {
                    val ore = g.ore.removePrefix("ore-")
                    val n = genCount.merge(ore, 1, Int::plus)!!
                    add(Poi("gen:$team:$ore-$n", "gen", g.x, g.y, g.z, g.ore))
                }
                // Mid nodes are stage-wide, identical per team: index them once.
                if (midCount.isEmpty()) {
                    for (node in sites.nodes) {
                        val ore = node.ore.removePrefix("ore-")
                        val n = midCount.merge(ore, 1, Int::plus)!!
                        add(Poi("mid-ore:$ore-$n", "mid-ore", node.x, node.y, node.z, node.ore))
                    }
                }
            }
            for (name in zones.names("")) zones.point(name)?.let { (x, y, z) -> add(Poi(name, "region", x, y, z)) }
            return PoiIndex(pois)
        }
    }
}
