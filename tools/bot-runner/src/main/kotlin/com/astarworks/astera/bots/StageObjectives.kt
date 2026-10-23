package com.astarworks.astera.bots

import java.io.File

/**
 * Objective knowledge read from the same `content/` the servers run: `matchmaking/queue.yaml` maps a lane to a stage,
 * `stages/<stage>.yaml` lists per-team spawns and named regions. The brain uses it when no enemy is in sight:
 *
 *  - regions named `core-<team>` are breakable cores (break-core): walk there and dig;
 *  - other `<thing>-<team>` regions (jewelry-blue, base-blue) and shared ones (tnt-spawn, wool-a) are walk-to points;
 *  - otherwise the nearest enemy team spawn.
 *
 * The YAML subset used by those files is tiny and regular, so it is read with regexes instead of a YAML library.
 */
class StageObjectives private constructor(private val spawns: List<Spawn>, private val regions: List<Region>) : ObjectiveSource {

    private data class Spawn(val team: String, val x: Double, val z: Double)

    private data class Region(val name: String, val cx: Double, val cy: Double, val cz: Double)

    override fun objectiveFor(spawnX: Double, spawnZ: Double): Objective? {
        val own = spawns.minByOrNull { (it.x - spawnX) * (it.x - spawnX) + (it.z - spawnZ) * (it.z - spawnZ) }?.team ?: return null
        val enemyTeams = spawns.map { it.team }.distinct().filter { it != own }
        // 1. enemy cores, 2. other enemy-owned regions, 3. shared objective regions
        val cores = regions.filter { r -> enemyTeams.any { r.name == "core-$it" } }
        pick(cores, spawnX, spawnZ)?.let { return Objective(it.name, it.cx, it.cy, it.cz, breakable = true) }
        val theirs = regions.filter { r -> enemyTeams.any { r.name.endsWith("-$it") } && !r.name.startsWith("base") }
        pick(theirs, spawnX, spawnZ)?.let { return Objective(it.name, it.cx, it.cy, it.cz, breakable = false) }
        val shared = regions.filter { r -> r.name.startsWith("wool") || r.name.startsWith("tnt") || r.name.startsWith("flag") }
        pick(shared, spawnX, spawnZ)?.let { return Objective(it.name, it.cx, it.cy, it.cz, breakable = false) }
        val enemySpawn = spawns.filter { it.team in enemyTeams }.minByOrNull { (it.x - spawnX) * (it.x - spawnX) + (it.z - spawnZ) * (it.z - spawnZ) }
            ?: return null
        return Objective("spawn-${enemySpawn.team}", enemySpawn.x, 0.0, enemySpawn.z, breakable = false)
    }

    private fun pick(list: List<Region>, fromX: Double, fromZ: Double): Region? =
        list.minByOrNull { (it.cx - fromX) * (it.cx - fromX) + (it.cz - fromZ) * (it.cz - fromZ) }

    companion object {
        private val LANE = Regex("^\\s*-\\s*id:\\s*(\\S+)")
        private val STAGE = Regex("^\\s*stage:\\s*(\\S+)")
        private val NAME = Regex("^\\s*-\\s*name:\\s*(\\S+)")
        private val TEAM = Regex("^\\s*-\\s*team:\\s*(\\S+)")
        private val XYZ = Regex("x:\\s*(-?[0-9.]+)\\s*,\\s*y:\\s*(-?[0-9.]+)\\s*,\\s*z:\\s*(-?[0-9.]+)")
        private val POSITION = Regex("^\\s*position:")
        private val MIN = Regex("^\\s*min:")
        private val MAX = Regex("^\\s*max:")

        /** The objectives of [lane] under [contentDir], or null when the content is not readable here. */
        fun load(contentDir: File, lane: String): StageObjectives? = runCatching {
            val stage = stageOf(File(contentDir, "matchmaking/queue.yaml").readLines(), lane) ?: return null
            parse(File(contentDir, "stages/$stage.yaml").readLines())
        }.getOrNull()

        internal fun stageOf(lines: List<String>, lane: String): String? {
            var current: String? = null
            for (line in lines) {
                LANE.find(line)?.let { current = it.groupValues[1].trim('"', '\'') }
                if (current == lane) STAGE.find(line)?.let { return it.groupValues[1].trim('"', '\'') }
            }
            return null
        }

        internal fun parse(lines: List<String>): StageObjectives {
            val spawns = ArrayList<Spawn>()
            val regions = ArrayList<Region>()
            var regionName: String? = null
            var min: Triple<Double, Double, Double>? = null
            var spawnTeam: String? = null
            for (line in lines) {
                NAME.find(line)?.let { regionName = it.groupValues[1]; min = null }
                TEAM.find(line)?.let { spawnTeam = it.groupValues[1]; regionName = null }
                if (line.trimStart().startsWith("- role:")) spawnTeam = null
                val xyz = XYZ.find(line)?.let { Triple(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }
                if (xyz == null) continue
                when {
                    MIN.containsMatchIn(line) -> min = xyz
                    MAX.containsMatchIn(line) -> {
                        val n = regionName
                        val lo = min
                        if (n != null && lo != null) {
                            regions += Region(n, (lo.first + xyz.first) / 2, lo.second, (lo.third + xyz.third) / 2)
                        }
                    }
                    POSITION.containsMatchIn(line) -> spawnTeam?.let { spawns += Spawn(it, xyz.first, xyz.third) }
                }
            }
            return StageObjectives(spawns, regions)
        }
    }
}
