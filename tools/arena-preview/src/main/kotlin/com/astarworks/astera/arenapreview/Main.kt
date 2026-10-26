package com.astarworks.astera.arenapreview

import com.astarworks.astera.bots.ArenaRouteGraph
import com.astarworks.astera.bots.PathFinder
import com.astarworks.astera.core.application.service.arena.ArenaStructureContent
import com.astarworks.astera.core.domain.stage.ArenaIslandGenerator
import com.astarworks.astera.core.domain.stage.ArenaIslandKind
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.lobbygen.BlockStateValidator
import com.astarworks.astera.lobbygen.BlockUse
import com.astarworks.astera.match.application.contents.GeneratorContents
import com.astarworks.astera.match.application.contents.StageContents
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.system.measureNanoTime

/** Real content + production generator + production A*. No server, test runner, mock registry or texture assets. */
fun main(args: Array<String>) {
    val content = Path.of(args.getOrElse(0) { "content" })
    val out = Path.of(args.getOrElse(1) { "build/arena-previews" })
    Files.createDirectories(out)
    val stages = StageContents()
    check(stages.bootstrap(content.resolve("stages")).failed == 0)
    val generators = GeneratorContents()
    check(generators.bootstrap(content.resolve("generators")).failed == 0)
    val parts = ArenaStructureContent.load(content)
    val report = StringBuilder("# Arena previews and measurements\n\nGenerated from shipped content and the production generator/PathFinder. Planning times are single-pass wall times on this host, excluding rendering; the first stage includes JVM warm-up. Paper placement/chunk timings require the manager's server run; inspect `Arena ... ready` maximum-slice logs (4 ms target, 1024-operation cap).\n\n")
    report.append("| Stage | Theme | Blocks | Generation ms | Digest ms | Bounds |\n|---|---|---:|---:|---:|---|\n")
    val routes = StringBuilder("\n## Routing (5000 nodes per A* search)\n\nTerrain treats every non-air block as solid, exactly as packet Terrain does; this is conservative for plants and banners. All chunks are known in this offline measurement. Live bots must progressively discover chunks.\n\n| Stage | Route | Direct complete | Direct nodes | Routed complete | Total local nodes | Max local nodes | Cells |\n|---|---|---|---:|---|---:|---:|---:|\n")
    val problems = mutableListOf<String>()
    val walks = StringBuilder("\n## Bot traversal (spawn to objective, centre, stars, towers; real PathFinder + route graph, re-plans on capped searches)\n\n| Stage | Route | Reached | Nodes | Last cell |\n|---|---|---|---:|---|\n")
    val metrics = StringBuilder("\n## Relief metrics\n\nGround = highest solid non-tree block below surface_y + 12. Flat ground = 5x5 equal height with 3 blocks of headroom. Free run = mean 16-ray distance at eye height on land (void ends the run). Cover clusters = 4-connected groups of >= 3 columns standing 2+ above local ground or carrying a full block.\n\n" + ArenaMetrics.HEADER)
    for (stage in stages.all().sortedBy { it.id.value }) {
        val start = System.nanoTime()
        val plan = ArenaIslandGenerator.generate(stage, parts)
        val ms = (System.nanoTime() - start) / 1_000_000.0
        val digestMs = measureNanoTime { plan.digest() } / 1_000_000.0
        val layout = stage.layout!!
        report.append("| ${stage.id.value} | ${layout.theme} | ${plan.blocks.size} | %.1f | %.1f | x=%d..%d z=%d..%d y=%d..%d |\n".format(ms, digestMs, plan.minX, plan.maxX, plan.minZ, plan.maxZ, plan.minY, plan.maxY))
        problems += BlockStateValidator.check(stage.id.value, plan.blocks.map { (p, state) -> BlockUse(p.x, p.y, p.z, state) })
        problems += plan.problems.map { "${stage.id.value}: $it" }
        if (layout.killY >= plan.minY) problems += "${stage.id.value}: kill plane intersects terrain"
        for (spawn in stage.spawnPoints.filter { it.role != SpawnRole.SPECTATOR }) {
            val p = spawn.position; val x = floor(p.x).toInt(); val y = floor(p.y).toInt(); val z = floor(p.z).toInt()
            if (!plan.isSolid(x, y - 1, z) || plan.isSolid(x, y, z) || plan.isSolid(x, y + 1, z)) problems += "${stage.id.value}: obstructed spawn $p"
        }
        for (g in generators.all().filter { it.matchStageId == stage.id.value }) {
            val x = floor(g.position.x).toInt(); val z = floor(g.position.z).toInt()
            if (layout.landmarks.none { it.x == x && it.z == z } || plan.stateAt(x, layout.surfaceY, z) != "minecraft:iron_block")
                problems += "${stage.id.value}: missing generator ${g.id}"
        }
        val finder = PathFinder { x, y, z -> plan.stateAt(x, y, z) != null }
        fun route(label: String, sx: Int, sz: Int, gx: Int, gz: Int, sy: Int = layout.surfaceY + 1, gy: Int = layout.surfaceY + 1) {
            val arrive = if (sy == gy) 2.0 else .6
            val result = finder.find(sx, sy, sz, gx + .5, gz + .5, gy.toDouble(), arrive)
            val points = if (kotlin.math.hypot((sx - gx).toDouble(), (sz - gz).toDouble()) > 48.0)
                ArenaRouteGraph(layout).route(sx + .5, sz + .5, gx + .5, gz + .5) else emptyList()
            var x = sx; var y = sy; var z = sz
            var complete = true; var expanded = 0; var maxLocal = 0; var cells = 0
            for (point in points + ArenaRouteGraph.Point(gx + .5, gy.toDouble(), gz + .5)) {
                val local = finder.find(x, y, z, point.x, point.z, point.y, arrive)
                expanded += local?.expanded ?: 0; maxLocal = maxOf(maxLocal, local?.expanded ?: 0)
                cells += local?.cells?.size ?: 0
                if (local?.complete != true) { complete = false; break }
                val last = local.cells.last(); x = last.x; y = last.y; z = last.z
            }
            routes.append("| ${stage.id.value} | $label | ${result?.complete ?: false} | ${result?.expanded ?: 0} | $complete | $expanded | $maxLocal | $cells |\n")
            if (!complete) problems += "${stage.id.value}: route $label incomplete even with topology"

        }
        val bases = layout.islands.filter { it.kind == ArenaIslandKind.BASE }
        for ((index, a) in bases.withIndex()) for (b in bases.drop(index + 1)) route("${a.id} -> ${b.id}", a.x, a.z, b.x, b.z)
        for (b in layout.bridges) {
            val a = layout.islands.first { it.id == b.from }; val c = layout.islands.first { it.id == b.to }
            route("bridge ${a.id} -> ${c.id}", a.x, a.z, c.x, c.z)
            route("bridge ${c.id} -> ${a.id}", c.x, c.z, a.x, a.z)
        }
        for (spawn in stage.spawnPoints.filter { it.team != null }.groupBy { it.team }.values.map { it.first() }) {
            for (base in bases.filter { it.team != spawn.team }) route("spawn ${spawn.team!!.value} -> ${base.id}", floor(spawn.position.x).toInt(), floor(spawn.position.z).toInt(), base.x, base.z)
        }
        for (region in stage.regions.filter { region -> listOf("core-", "jewelry-", "wool-", "tnt-spawn").any { region.name?.startsWith(it) == true } }) {
            val a = bases.firstOrNull { it.team?.value != region.name!!.removePrefix("core-") } ?: layout.islands.first()
            route(region.name!!, a.x, a.z, floor((region.min.x + region.max.x) / 2).toInt(), floor((region.min.z + region.max.z) / 2).toInt())
        }
        for ((index, placement) in layout.structures.withIndex()) {
            if (placement.part !in listOf("castle-tower", "castle-keep")) continue
            val tower = placement.part == "castle-tower"
            val start = placement.transform(com.astarworks.astera.core.domain.stage.ArenaBlockPos(if (tower) 7 else 13, 1, 0), layout.surfaceY)
            val goal = placement.transform(com.astarworks.astera.core.domain.stage.ArenaBlockPos(if (tower) 0 else 4, if (tower) 17 else 10, if (tower) 0 else 4), layout.surfaceY)
            route("climb ${placement.part} #$index", start.x, start.z, goal.x, goal.z, start.y, goal.y)
        }
        problems += TraversalCheck.run(stage, plan, generators.all().filter { it.matchStageId == stage.id.value }.map { it.position.x to it.position.z }, walks)
        run {
            val g = PathFinder { x, y, z -> plan.stateAt(x, y, z) != null }
            metrics.append(ArenaMetrics.measure(stage, plan, g, ArenaRouteGraph(layout)).row(stage.id.value)).append('\n')
        }
        ImageIO.write(ArenaMetrics.heightmap(stage, plan), "png", out.resolve("${stage.id.value}-height.png").toFile())
        ImageIO.write(ArenaRenderer.top(stage, plan), "png", out.resolve("${stage.id.value}-top.png").toFile())
        ImageIO.write(ArenaRenderer.iso(stage, plan), "png", out.resolve("${stage.id.value}-iso.png").toFile())
        ImageIO.write(ArenaRenderer.section(stage, plan), "png", out.resolve("${stage.id.value}-section.png").toFile())
        println("${stage.id.value}: ${plan.blocks.size} blocks, %.1f ms".format(ms))
    }
    report.append(metrics)
    report.append(routes)
    report.append(walks)
    report.append("\n## Content / routing problems\n\n")
    report.append(if (problems.isEmpty()) "None.\n" else problems.joinToString("\n", postfix = "\n") { "- $it" })
    Files.writeString(out.resolve("report.md"), report)
    println("Wrote ${out.resolve("report.md")}; ${problems.size} problem(s)")
    check(problems.isEmpty()) { "Preview validation failed: ${problems.joinToString()}" }
}
