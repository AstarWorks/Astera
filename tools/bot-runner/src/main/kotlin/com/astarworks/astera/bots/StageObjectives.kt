package com.astarworks.astera.bots

import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.contents.GeneratorContents
import com.astarworks.astera.match.application.contents.StageContents
import java.io.File

/** Mode objectives and navigation share the server's actual stage/generator loaders (including YAML validation). */
class StageObjectives private constructor(
    private val spawns: List<Spawn>,
    private val regions: List<Region>,
    private val generators: List<Pair<Double, Double>>,
    private val graph: ArenaRouteGraph?,
) : ObjectiveSource {
    override fun starPoints(): List<Pair<Double, Double>> = generators
    override fun routeGraph(): ArenaRouteGraph? = graph

    /** Centre (x, z) of the stage region [name] (e.g. `tnt-spawn`, `base-blue`, `wool-b`), or null when the stage has none. */
    fun zoneCentre(name: String): Pair<Double, Double>? = regions.firstOrNull { it.name == name }?.let { it.cx to it.cz }

    /** Names of the stage regions that start with [prefix] (e.g. every `wool-` control point), in stage order. */
    fun zoneNames(prefix: String): List<String> = regions.map { it.name }.filter { it.startsWith(prefix) }

    private data class Spawn(val team: String, val x: Double, val z: Double)
    private data class Region(val name: String, val cx: Double, val cy: Double, val cz: Double)

    override fun objectiveFor(spawnX: Double, spawnZ: Double): Objective? {
        val own = spawns.minByOrNull { (it.x - spawnX) * (it.x - spawnX) + (it.z - spawnZ) * (it.z - spawnZ) }?.team ?: return null
        val enemyTeams = spawns.map { it.team }.distinct().filter { it != own }
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

        fun load(contentDir: File, lane: String): StageObjectives? = runCatching {
            val stageId = stageOf(File(contentDir, "matchmaking/queue.yaml").readLines(), lane) ?: return null
            val contents = StageContents()
            check(contents.bootstrap(contentDir.toPath().resolve("stages")).failed == 0)
            val stage = contents.find(StageId(stageId)) ?: return null
            val generators = GeneratorContents()
            check(generators.bootstrap(contentDir.toPath().resolve("generators")).failed == 0)
            StageObjectives(
                stage.spawnPoints.mapNotNull { point -> point.team?.let { Spawn(it.value, point.position.x, point.position.z) } },
                stage.regions.mapNotNull { region -> region.name?.let { Region(it, (region.min.x + region.max.x) / 2, region.min.y, (region.min.z + region.max.z) / 2) } },
                generators.all().filter { it.matchStageId == stageId }.map { it.position.x to it.position.z },
                stage.layout?.let(::ArenaRouteGraph),
            )
        }.onFailure { System.err.println("Bot stage '$lane' could not load: ${it.message}") }.getOrNull()

        private fun stageOf(lines: List<String>, lane: String): String? {
            var current: String? = null
            for (line in lines) {
                LANE.find(line)?.let { current = it.groupValues[1].trim('"', '\'') }
                if (current == lane) STAGE.find(line)?.let { return it.groupValues[1].trim('"', '\'') }
            }
            return null
        }
    }
}
