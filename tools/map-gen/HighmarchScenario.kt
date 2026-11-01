import com.astarworks.astera.bots.PathFinder
import com.astarworks.astera.core.application.service.arena.ArenaImportContent
import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaIslandGenerator
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.contents.OreNodeContents
import com.astarworks.astera.match.application.contents.StageContents
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor
import kotlin.math.hypot

/** Integration scenario, not a unit test: shipped YAML -> production loaders -> imported plan -> real bot PathFinder.
 * Run via run-scenario.sh after make arena-preview; no server, JUnit, or game-sim Gradle test task.
 */
fun main(args: Array<String>) {
    val root = Path.of(args[0])
    val coordinates = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
    val reportPath = Path.of(args[2])
    val stages = StageContents()
    check(stages.bootstrap(root.resolve("content/stages")).failed == 0)
    val stage = checkNotNull(stages.find(StageId("annihilation-arena")))
    val imports = ArenaImportContent.load(root.resolve("content"))
    val plan = ArenaIslandGenerator.generate(stage, emptyMap(), imports)
    check(plan.problems.isEmpty()) { plan.problems }
    check(stage.layout!!.islands.isEmpty() && stage.layout!!.bridges.isEmpty() && stage.layout!!.structures.isEmpty())
    check(stage.pillars.size == 9)
    // Match placement replaces exactly these nine exposed cells; include them in navigation collision.
    val blocks = plan.blocks.toMutableMap()
    stage.pillars.forEach { p -> blocks[ArenaBlockPos(p.position.x.toInt(), p.position.y.toInt(), p.position.z.toInt())] = p.block.key }
    val ores = OreNodeContents()
    check(ores.bootstrap(root.resolve("content/ore-nodes")).failed == 0)
    val nodes = checkNotNull(ores.find("annihilation-arena")).nodes.flatMap { it.positions }
    check(nodes.size == 74)
    nodes.forEach { p -> blocks[ArenaBlockPos(p.x.toInt(), p.y.toInt(), p.z.toInt())] = "minecraft:stone" }
    val finder = PathFinder { x, y, z -> blocks.containsKey(ArenaBlockPos(x, y, z)) }
    val rows = StringBuilder("# Highmarch integration traversal\n\nShipped import, stage and ore loaders + production PathFinder, cap 5000 with at most 12 progress replans; paths count cells as TraversalCheck does (5.6 cells/s). Diagonals count as one cell, so these are comparable estimates, not elapsed play time.\n\n| Scenario | Route | Reached | Path blocks | Sprint s |\n|---|---|---|---:|---:|\n")
    var failures = 0
    fun walk(label: String, start: Triple<Int, Int, Int>, goal: Triple<Int, Int, Int>, arrive: Double = .6): Int? {
        var (x, y, z) = start
        var cells = 0
        var complete = false
        repeat(13) {
            if (complete) return@repeat
            val r = finder.find(x, y, z, goal.first + .5, goal.third + .5, goal.second.toDouble(), arrive)
            if (r == null) return@repeat
            cells += r.cells.size
            val end = r.cells.last()
            val moved = end.x != x || end.y != y || end.z != z
            x = end.x; y = end.y; z = end.z
            complete = r.complete
            if (!moved && !complete) return@repeat
        }
        rows.append("| HighmarchScenario | $label | $complete | $cells | %.1f |\n".format(cells / 5.6))
        if (!complete) { failures++; System.err.println("FAILED $label: last $x $y $z, goal $goal") }
        return if (complete) cells else null
    }
    fun cell(p: com.astarworks.astera.core.domain.geometry.Vec3) = Triple(floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
    val rim = mapOf("red" to Triple(0, 65, -42), "blue" to Triple(36, 65, 21), "yellow" to Triple(-36, 65, 21))
    for (team in listOf("red", "blue", "yellow")) {
        val spawns = stage.spawnPoints.filter { it.team?.value == team }
        check(spawns.size == 4)
        for ((i, spawn) in spawns.withIndex()) {
            val length = walk("$team spawn#$i -> Mid rim", cell(spawn.position), rim.getValue(team))
            if (length != null && length / 5.6 !in 12.0..18.0) {
                failures++; System.err.println("Rim pacing $team#$i: ${length / 5.6} s")
            }
            for (enemy in stage.pillars.filter { it.team.value != team && it.tier.ordinalValue == 1 }) {
                val goal = Triple(enemy.position.x.toInt(), enemy.position.y.toInt(), enemy.position.z.toInt() + 5)
                val n = walk("$team spawn#$i -> ${enemy.team.value} tier-1", cell(spawn.position), goal, 2.0)
                if (n != null && n / 5.6 !in 30.0..45.0) {
                    failures++; System.err.println("Enemy pacing $team#$i -> ${enemy.team.value}: ${n / 5.6} s")
                }
            }
        }
        val start = cell(spawns.first().position)
        for (pillar in stage.pillars.filter { it.team.value == team }) {
            val p = cell(pillar.position)
            for ((dx, dz) in listOf(0 to 5, 0 to -5, 5 to 0, -5 to 0)) {
                val goal = Triple(p.first + dx, p.second, p.third + dz)
                check(finder.standable(goal.first, goal.second, goal.third)) { "pillar approach is obstructed: $goal" }
                walk("$team tier-${pillar.tier.ordinalValue} approach $dx,$dz", start, goal)
            }
        }
        for ((name, value) in coordinates.getValue("climbs").jsonObject) {
            if (!name.startsWith(team) && name !in listOf("chapel", "east-crane", "south-crane")) continue
            val p = value.jsonArray.map { it.jsonPrimitive.int }
            val goal = Triple(p[0], p[1], p[2])
            if (!finder.standable(goal.first, goal.second, goal.third)) { failures++; System.err.println("$name has no standable goal $goal") }
            walk("$team spawn -> $name", start, goal)
        }
        for ((name, value) in coordinates.getValue("stars").jsonObject) {
            if (!name.startsWith("cache-$team")) continue
            val p = value.jsonArray.map { it.jsonPrimitive.int }
            walk("$team spawn -> $name", start, Triple(p[0], p[1], p[2]), 2.0)
        }
    }
    // All 74 ore cells are still air before OreNodeService writes them, and have a mining stance beside them.
    val red = cell(stage.spawnPoints.first { it.team?.value == "red" }.position)
    for ((i, p) in nodes.withIndex()) {
        val x = p.x.toInt(); val y = p.y.toInt(); val z = p.z.toInt()
        check(!plan.blocks.containsKey(ArenaBlockPos(x, y, z))) { "ore cell $i occupied before service placement" }
        val stances = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (dx, dz) -> Triple(x + dx, y, z + dz) }
        val goal = checkNotNull(stances.firstOrNull { finder.standable(it.first, it.second, it.third) }) { "ore $i has no mining stance" }
        walk("red spawn -> ore#$i", red, goal, 1.5)
    }
    val radius = coordinates.getValue("center_wave_radius").jsonPrimitive.int
    fun safeScatter(r: Int): Boolean {
        // Runtime generators are at block centres. Include EVERY cell touched by the continuous disk,
        // rather than sampling only integer centres, and include ore placed by OreNodeService.
        for (x in -r - 1..r + 1) for (z in -r - 1..r + 1) {
            val dx = (kotlin.math.abs(x.toDouble()) - .5).coerceAtLeast(0.0)
            val dz = (kotlin.math.abs(z.toDouble()) - .5).coerceAtLeast(0.0)
            if (hypot(dx, dz) > r) continue
            if (!plan.isSolid(x, 50, z) || blocks.containsKey(ArenaBlockPos(x, 51, z))) return false
        }
        return true
    }
    check(safeScatter(radius)) { "unsafe wave scatter radius $radius" }
    check(!safeScatter(radius + 1)) { "advertised scatter radius is not the largest safe integer radius" }
    Files.writeString(reportPath, rows.toString())
    println("HighmarchScenario: ${rows.lines().count { it.startsWith("| HighmarchScenario") }} routes, $failures failures; ${nodes.size} exposed ore pockets; radius-$radius wave ground verified. $reportPath")
    check(failures == 0) { "$failures Highmarch integration failures" }
}
