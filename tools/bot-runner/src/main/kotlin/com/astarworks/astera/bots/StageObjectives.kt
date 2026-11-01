package com.astarworks.astera.bots

import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.config.GeneratorYamlConfig
import com.astarworks.astera.match.application.contents.GeneratorContents
import com.astarworks.astera.match.application.contents.OreNodeContents
import com.astarworks.astera.match.application.contents.StageContents
import java.io.File

/** Mode objectives and navigation share the server's actual stage/generator loaders (including YAML validation). */
class StageObjectives private constructor(
    private val spawns: List<Spawn>,
    private val regions: List<Region>,
    private val generators: List<Pair<Double, Double>>,
    private val graph: ArenaRouteGraph?,
    private val pillars: List<ModePlanner.PillarSite> = emptyList(),
    private val economy: Economy = Economy(),
    private val caches: List<ObjectiveSource.CachePoint> = emptyList(),
) : ObjectiveSource {
    /** Stage-local shop NPCs, base ore generators and Mid ore nodes of every team, plus the shop's upgrade-price ladder. */
    class Economy(
        val shops: Map<String, EconomyPlanner.Site> = emptyMap(),
        val generators: Map<String, List<EconomyPlanner.Site>> = emptyMap(),
        val nodes: List<EconomyPlanner.Site> = emptyList(),
        val ladder: List<EconomyPlanner.Companion.Buy> = emptyList(),
    )

    override fun economySites(team: String): EconomyPlanner.Sites? {
        val shop = economy.shops[team] ?: return null
        return EconomyPlanner.Sites(shop, economy.generators[team].orEmpty(), economy.nodes)
    }

    override fun economyLadder(): List<EconomyPlanner.Companion.Buy> = economy.ladder

    override fun starPoints(): List<Pair<Double, Double>> = generators
    override fun cachePoints(): List<ObjectiveSource.CachePoint> = caches
    override fun routeGraph(): ArenaRouteGraph? = graph

    override fun zones(): ModePlanner.Zones = object : ModePlanner.Zones {
        override fun point(name: String) = regions.firstOrNull { it.name == name }?.let { Triple(it.cx, it.cy, it.cz) }
        override fun names(prefix: String) = zoneNames(prefix)
        override fun pillars() = this@StageObjectives.pillars
        override fun spawns() = this@StageObjectives.spawns.map { Triple(it.team, it.x, it.z) }
    }

    override fun ownTeamAt(spawnX: Double, spawnZ: Double): String? =
        spawns.minByOrNull { (it.x - spawnX) * (it.x - spawnX) + (it.z - spawnZ) * (it.z - spawnZ) }?.team

    override fun teams(): List<String> = spawns.map { it.team }.distinct()

    /** Centre (x, z) of the stage region [name] (e.g. `tnt-spawn`, `base-blue`, `wool-b`), or null when the stage has none. */
    fun zoneCentre(name: String): Pair<Double, Double>? = regions.firstOrNull { it.name == name }?.let { it.cx to it.cz }

    /** Names of the stage regions that start with [prefix] (e.g. every `wool-` control point), in stage order. */
    fun zoneNames(prefix: String): List<String> = regions.map { it.name }.filter { it.startsWith(prefix) }

    private data class Spawn(val team: String, val x: Double, val z: Double)
    private data class Region(val name: String, val cx: Double, val cy: Double, val cz: Double)

    override fun objectiveFor(spawnX: Double, spawnZ: Double): Objective? {
        val own = spawns.minByOrNull { (it.x - spawnX) * (it.x - spawnX) + (it.z - spawnZ) * (it.z - spawnZ) }?.team ?: return null
        val enemyTeams = spawns.map { it.team }.distinct().filter { it != own }
        val theirs = regions.filter { r -> enemyTeams.any { r.name.endsWith("-$it") } && !r.name.startsWith("base") }
        pick(theirs, spawnX, spawnZ)?.let { return Objective(it.name, it.cx, it.cy, it.cz) }
        val shared = regions.filter { r -> r.name.startsWith("wool") || r.name.startsWith("tnt") || r.name.startsWith("flag") }
        pick(shared, spawnX, spawnZ)?.let { return Objective(it.name, it.cx, it.cy, it.cz) }
        val enemySpawn = spawns.filter { it.team in enemyTeams }.minByOrNull { (it.x - spawnX) * (it.x - spawnX) + (it.z - spawnZ) * (it.z - spawnZ) }
            ?: return null
        return Objective("spawn-${enemySpawn.team}", enemySpawn.x, 0.0, enemySpawn.z)
    }

    private fun pick(list: List<Region>, fromX: Double, fromZ: Double): Region? =
        list.minByOrNull { (it.cx - fromX) * (it.cx - fromX) + (it.cz - fromZ) * (it.cz - fromZ) }

    companion object {
        private val LANE = Regex("^\\s*-\\s*id:\\s*(\\S+)")
        private val STAGE = Regex("^\\s*stage:\\s*(\\S+)")

        /** Objectives of an already loaded [stage] with its star pedestals; shared with the offline arena traversal check. */
        fun of(stage: Stage, starPoints: List<Pair<Double, Double>>, economy: Economy = Economy(), caches: List<ObjectiveSource.CachePoint> = emptyList()): StageObjectives = StageObjectives(
            stage.spawnPoints.mapNotNull { point -> point.team?.let { Spawn(it.value, point.position.x, point.position.z) } },
            stage.regions.mapNotNull { region -> region.name?.let { Region(it, (region.min.x + region.max.x) / 2, region.min.y, (region.min.z + region.max.z) / 2) } },
            starPoints,
            stage.layout?.let(::ArenaRouteGraph),
            stage.pillars.map {
                ModePlanner.PillarSite(it.team.value, it.tier.ordinalValue, Math.floor(it.position.x).toInt(), Math.floor(it.position.y).toInt(), Math.floor(it.position.z).toInt(), it.affinity.name.lowercase())
            },
            economy,
            caches,
        )

        fun load(contentDir: File, lane: String): StageObjectives? = runCatching {
            val stageId = stageOf(File(contentDir, "matchmaking/queue.yaml").readLines(), lane) ?: return null
            val contents = StageContents()
            check(contents.bootstrap(contentDir.toPath().resolve("stages")).failed == 0)
            val stage = contents.find(StageId(stageId)) ?: return null
            val generators = GeneratorContents()
            check(generators.bootstrap(contentDir.toPath().resolve("generators")).failed == 0)
            val here = generators.all().filter { it.matchStageId == stageId }
            // ADR-0318: a timer generator pops on one of its candidate spots (rotating), so every candidate is a place stars can be.
            val spotPoints = here.filter { it.cache == null && it.dropItem == null }
                .flatMap { g -> if (g.spots.isEmpty()) listOf(g.position.x to g.position.z) else g.spots.map { it.x to it.z } }
            val cachePoints = here.filter { it.cache != null }
                .flatMap { g -> g.spots.map { ObjectiveSource.CachePoint(it.x, it.y, it.z, (g.cache!!.respawnSeconds * 1000).toLong(), g.affiliation.takeIf { a -> a.kind.equals("team", ignoreCase = true) }?.teamId) } }
            of(stage, spotPoints, economyOf(contentDir, stage, stageId, here), cachePoints)
        }.onFailure { System.err.println("Bot stage '$lane' could not load: ${it.message}") }.getOrNull()

        /** Shop NPCs of [stage], the team base ore generators of [stageId] and the pickaxe Mid nodes (axe / shovel nodes are not dug by the economy yet) of its `content/ore-nodes/` file. */
        private fun economyOf(contentDir: File, stage: Stage, stageId: String, stageGenerators: Collection<GeneratorYamlConfig>): Economy {
            val shops = stage.shopNpcs.associate { it.team.value to EconomyPlanner.Site(it.x, it.y, it.z) }
            val gens = stageGenerators.filter { it.dropItem != null && it.affiliation.kind.equals("team", ignoreCase = true) && it.affiliation.teamId != null }
                .groupBy({ it.affiliation.teamId!! }, { EconomyPlanner.Site(it.position.x, it.position.y, it.position.z, it.dropItem!!) })
            val nodes = ArrayList<EconomyPlanner.Site>()
            val properTools = com.astarworks.astera.core.application.skillengine.compose.blockProperTools(contentDir.toPath())
            val nodeFile = contentDir.toPath().resolve("ore-nodes")
            val oreNodes = OreNodeContents()
            if (java.nio.file.Files.isDirectory(nodeFile)) {
                check(oreNodes.bootstrap(nodeFile).failed == 0)
                oreNodes.find(stage.templateId.value)?.nodes?.forEach { group ->
                    val ore = group.drops.singleOrNull()?.itemId ?: return@forEach
                    if (requiredTool(properTools, group.block) != com.astarworks.astera.core.domain.world.ToolKind.PICKAXE) return@forEach
                    for (p in group.positions) nodes += EconomyPlanner.Site(p.x + 0.5, p.y, p.z + 0.5, ore)
                    group.region?.let { r ->
                        for (x in r.min.x.toInt()..r.max.x.toInt()) for (y in r.min.y.toInt()..r.max.y.toInt()) for (z in r.min.z.toInt()..r.max.z.toInt()) {
                            nodes += EconomyPlanner.Site(x + 0.5, y.toDouble(), z + 0.5, ore)
                        }
                    }
                }
            }
            val shopYaml = File(contentDir, "shops/match-shop.yaml")
            val ladder = if (shopYaml.isFile) EconomyPlanner.parseShoppingList(shopYaml.readText()) else emptyList()
            return Economy(shops, gens, nodes, ladder)
        }

        /** A node's proper tool from block-families.yaml (the same source the match uses); an unlisted block fails loudly. */
        private fun requiredTool(properTools: Map<String, com.astarworks.astera.core.domain.world.ToolKind>, block: String) =
            properTools[block] ?: error("ore node block '$block' has no tool family in content/engine/block-families.yaml")

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
