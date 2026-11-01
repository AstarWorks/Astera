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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
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
    // HighmarchImportIsolation: real RLE loader -> production plan. Air holes and outer void must
    // stay absent; the only planned overlay is each shipped generator's iron pedestal.
    val imported = checkNotNull(imports["annihilation-highmarch"])
    val pedestals = stage.layout!!.landmarks.map { ArenaBlockPos(it.x, plan.surfaceY, it.z) }.toSet()
    check(plan.blocks.size == imported.blocks.size) { "Highmarch import added/dropped cells" }
    for ((p, state) in plan.blocks) {
        check(state == if (p in pedestals) "minecraft:iron_block" else imported.blocks[p]) {
            "HighmarchImportIsolation: foreign state $state at $p (import=${imported.blocks[p]})"
        }
        check(p.x in -512..512 && p.z in -512..512 && p.y in -64..319)
        check(state.substringBefore('[') !in setOf("minecraft:barrier", "minecraft:structure_void", "minecraft:light")) {
            "HighmarchImportIsolation: visible/debug marker $state at $p"
        }
    }
    for ((x, y, z) in listOf(Triple(0, 64, 203), Triple(177, 64, -101), Triple(-177, 64, -101), Triple(0, 100, -187))) {
        check(plan.stateAt(x, y, z) == null) { "HighmarchImportIsolation: legacy frost cell ($x,$y,$z) is not void" }
    }
    println("HighmarchImportIsolation: ${plan.blocks.size} cells match the shipped import and pedestal overlay; legacy cells are void")
    // Match placement replaces exactly these nine exposed cells; include them in navigation collision.
    val blocks = plan.blocks.toMutableMap()
    stage.pillars.forEach { p -> blocks[ArenaBlockPos(p.position.x.toInt(), p.position.y.toInt(), p.position.z.toInt())] = p.block.key }
    val ores = OreNodeContents()
    check(ores.bootstrap(root.resolve("content/ore-nodes")).failed == 0)
    val groups = checkNotNull(ores.find("annihilation-arena")).nodes
    val nodes = groups.flatMap { it.positions }
    for (group in groups) {
        val name = group.block.removePrefix("astera:ore_").removeSuffix("_node")
        val expected = coordinates.getValue("ore").jsonObject.getValue(name).jsonArray
            .map { it.jsonArray.map { n -> n.jsonPrimitive.int } }
        check(group.positions.map { listOf(it.x.toInt(), it.y.toInt(), it.z.toInt()) } == expected) {
            "$name handoff coordinates differ from shipped ore content"
        }
    }
    nodes.forEach { p -> blocks[ArenaBlockPos(p.x.toInt(), p.y.toInt(), p.z.toInt())] = "minecraft:stone" }
    val finder = PathFinder { x, y, z -> blocks.containsKey(ArenaBlockPos(x, y, z)) }
    // Every fixed supply cell stays open, including candidates not selected by the shipped generators.
    for ((name, value) in coordinates.getValue("stars").jsonObject) {
        val p = value.jsonArray.map { it.jsonPrimitive.int }
        check(finder.standable(p[0], p[1], p[2])) { "fixed star spot obstructed: $name $p" }
    }
    // Tier crowns escalate above their objective floors, with a visible team material and light.
    for (pillar in stage.pillars) {
        val x = pillar.position.x.toInt(); val y = pillar.position.y.toInt(); val z = pillar.position.z.toInt()
        val crown = y + 8 + pillar.tier.ordinalValue * 2
        check(plan.stateAt(x, crown, z) == "minecraft:${pillar.team.value}_concrete")
        check(plan.stateAt(x, crown + 2, z) == "minecraft:glowstone")
    }
    // Art-pass regressions use the real decoded plan: broad coloured lips and
    // substantial ore/raw seams around EVERY quarry pocket, with no added nodes.
    val seams = mapOf(
        "iron" to setOf("iron_ore", "raw_iron_block"),
        "gold" to setOf("gold_ore", "raw_gold_block"),
        "diamond" to setOf("deepslate_diamond_ore", "diamond_block"),
        "emerald" to setOf("emerald_ore", "emerald_block"),
    )
    for ((name, materials) in seams) {
        for (value in coordinates.getValue("ore").jsonObject.getValue(name).jsonArray) {
            val (x, y, z) = value.jsonArray.map { it.jsonPrimitive.int }
            if (hypot(x.toDouble(), z.toDouble()) > 43) continue
            val mineralCount = (-6..6).sumOf { dx -> (-6..6).sumOf { dz ->
                (-2..4).count { dy -> plan.stateAt(x + dx, y + dy, z + dz)?.removePrefix("minecraft:") in materials }
            } }
            check(mineralCount >= 15) { "$name pocket $x,$y,$z has only $mineralCount mineral blocks" }
        }
    }
    for (colour in listOf("calcite", "yellow_concrete", "light_blue_concrete", "lime_concrete", "blue_concrete")) {
        val visible = plan.blocks.count { (p, state) ->
            hypot(p.x.toDouble(), p.z.toDouble()) <= 42 && p.y in 50..64 &&
                state == "minecraft:$colour" && !plan.isSolid(p.x, p.y + 1, p.z)
        }
        check(visible >= 20) { "quarry lip colour $colour lost: $visible exposed blocks" }
    }
    for ((x, z) in listOf(0 to -65, 44 to 47, -44 to 47)) {
        check(plan.stateAt(x, 56, z) == "minecraft:water") { "mid-lane gorge missing at $x,$z" }
    }
    for ((x, z) in listOf(-14 to -62, 41 to 53, -41 to 53)) {
        check(plan.stateAt(x, 66, z) == "minecraft:lantern") { "wayside shrine missing at $x,$z" }
    }
    // Check the final imported plan: bridge/town passes must never bury a ladder.
    for (degrees in 0 until 360 step 20) {
        val angle = Math.toRadians(degrees.toDouble())
        for (radius in listOf(62 + 2 * cos(3 * angle), 68 + 2 * cos(3 * angle))) {
            val x = (cos(angle) * radius).roundToInt(); val z = (sin(angle) * radius).roundToInt()
            check((57..64).all { plan.stateAt(x, it, z)?.startsWith("minecraft:ladder") == true }) { "buried climb-out at $x,$z" }
            check(!plan.isSolid(x, 65, z) && !plan.isSolid(x, 66, z)) { "blocked climb-out headroom at $x,$z" }
        }
    }
    // R4b: verify the decoded import, not just the generator's geometry.
    for (x in -54..54) for (z in -54..54) {
        if (hypot(x.toDouble(), z.toDouble()) > 42 && hypot(x.toDouble(), z.toDouble()) <= 54)
            check(plan.isSolid(x, 64, z) && plan.stateAt(x, 64, z) != "minecraft:water") { "broken dry band $x,$z" }
    }
    for (material in listOf("coarse_dirt", "gravel", "tuff", "andesite", "cobbled_deepslate")) {
        val exposed = plan.blocks.count { (p, state) ->
            hypot(p.x.toDouble(), p.z.toDouble()) <= 42 && p.y in 50..64 &&
                state == "minecraft:$material" && !plan.isSolid(p.x, p.y + 1, p.z)
        }
        check(exposed >= 30) { "lost exposed geology $material: $exposed" }
    }
    for (x in listOf(-88, 88)) {
        check(plan.stateAt(x, 56, -25) == "minecraft:water")
        check(plan.stateAt(x, 64, -25) == "minecraft:coarse_dirt") { "lost ridge crossing $x,-25" }
    }
    if (args.getOrNull(3) != "--baseline") {
        // Quality regression on the real shipped import: thick noisy perimeter,
        // rolling outer turf, mixed veteran woodland and visible path shoulders.
        check(plan.blocks.none { (p, state) -> hypot(p.x.toDouble(), p.z.toDouble()) <= 42 && state == "minecraft:grass_block" }) {
            "outer hill pass left turf floating above the quarry"
        }
        val turf = plan.blocks.filter { (p, state) ->
            state == "minecraft:grass_block" && hypot(p.x.toDouble(), p.z.toDouble()) > 75
        }
        check(turf.keys.map { it.y }.distinct().size >= 7) { "outer ring became a flat lawn" }
        check(turf.count { it.key.y >= 67 } >= 1500) { "outer hills lost" }
        fun count(material: String) = plan.blocks.values.count { it.substringBefore('[') == "minecraft:$material" }
        for (species in listOf("oak", "birch", "spruce", "dark_oak")) {
            check(count("${species}_log") >= 50 && count("${species}_leaves") >= 200) { "lost woodland species $species" }
        }
        val stemLengths = plan.blocks.filterValues { it.endsWith("_log[axis=y]") }.mapNotNull { (p, state) ->
            if (plan.stateAt(p.x, p.y - 1, p.z) == state) null else {
                var length = 0
                while (plan.stateAt(p.x, p.y + length, p.z) == state) length++
                length
            }
        }.toSet()
        check(stemLengths.containsAll(listOf(5, 7, 10))) { "lost three woodland scales: $stemLengths" }
        check(plan.blocks.any { (p, state) ->
            state in setOf("minecraft:oak_log[axis=y]", "minecraft:dark_oak_log[axis=y]") &&
                plan.stateAt(p.x + 1, p.y, p.z) == state && plan.stateAt(p.x, p.y, p.z + 1) == state &&
                plan.stateAt(p.x + 1, p.y, p.z + 1) == state
        }) { "lost multi-trunk veteran trees" }
        val roundedCrowns = plan.blocks.count { (p, state) ->
            state.endsWith("_log[axis=y]") && p.y > 67 &&
                listOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2).all { (dx, dz) ->
                    plan.stateAt(p.x + dx, p.y, p.z + dz)?.contains("_leaves[") == true
                } && listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2).count { (dx, dz) ->
                    plan.stateAt(p.x + dx, p.y, p.z + dz)?.contains("_leaves[") != true
                } >= 3
        }
        check(roundedCrowns >= 20) { "woodland reverted to square crowns: $roundedCrowns" }
        check(count("spruce_wall_sign") > 0) { "lost roadside posts" }
        check(count("fern") >= 100 && count("short_grass") >= 300) { "lost undergrowth" }
        check(count("dirt_path") >= 500 && count("gravel") >= 500) { "lost blended tracks" }
        // Quality2: the decoded island contracts downward instead of a thick rim extrusion.
        val rock = setOf("stone", "andesite", "tuff", "deepslate", "dirt", "coal_ore", "deepslate_iron_ore")
        val sections = listOf(30, 40, 50, 60).map { y ->
            plan.blocks.count { (p, state) -> p.y == y && state.removePrefix("minecraft:").substringBefore('[') in rock }
        }
        check(sections.zipWithNext().all { (lower, upper) -> lower < upper * .9 }) { "underside no longer narrows: $sections" }
        val bottoms = plan.blocks.keys.groupingBy { it.x to it.z }.fold(Int.MAX_VALUE) { y, p -> minOf(y, p.y) }
        check(bottoms.values.distinct().size >= 30) { "flat island bottom" }
        var maxWall = 0
        for ((xz, _) in bottoms) {
            var run = 0
            for (y in 20..60) {
                val state = plan.stateAt(xz.first, y, xz.second)?.removePrefix("minecraft:")?.substringBefore('[')
                val exposed = state in rock && listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dz) ->
                    !plan.isSolid(xz.first + dx, y, xz.second + dz)
                }
                run = if (exposed) run + 1 else 0
                maxWall = maxOf(maxWall, run)
            }
        }
        check(maxWall <= 6) { "vertical underside wall: $maxWall" }
        check(count("hanging_roots") >= 20) { "lost rim roots" }
        check(plan.blocks.all { (p, state) ->
            state != "minecraft:hanging_roots" || plan.isSolid(p.x, p.y + 1, p.z)
        }) { "unsupported hanging root tip" }
        check(plan.blocks.count { (p, state) -> p.y < 40 && state.startsWith("minecraft:water") } >= 20) { "lost outlet falls" }
        check(plan.blocks.any { (p, state) ->
            state == "minecraft:spruce_log[axis=y]" && p.y > 65 &&
                plan.stateAt(p.x + 1, p.y, p.z) == state && plan.stateAt(p.x, p.y, p.z + 1) == state &&
                plan.stateAt(p.x + 1, p.y, p.z + 1) == state
        }) { "lost giant spruce trunks" }
        println("HighmarchQuality2: underside slices $sections; longest exposed wall $maxWall; rooted rim, waterfalls and giant spruce")
    }
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
    // Measure identical endpoints for baseline and candidate. Entry is the first cell
    // inside each of the three gate throats, not the Mid bridge or a hand-picked road.
    val teams = listOf("red", "blue", "yellow")
    fun local(team: String, u: Int, v: Int, y: Int = 65): Triple<Int, Int, Int> = when (team) {
        "red" -> Triple(u, y, -108 + v)
        "blue" -> Triple(94 - v, y, 54 + u)
        else -> Triple(-94 + v, y, 54 - u)
    }
    val baseline = args.getOrNull(3) == "--baseline"
    if (!baseline) {
        for ((name, values) in coordinates.getValue("ore").jsonObject) {
            val mid = values.jsonArray.map { it.jsonArray.map { p -> p.jsonPrimitive.int } }
                .filter { hypot(it[0].toDouble(), it[2].toDouble()) <= 43 }
            check(mid.isNotEmpty() && mid.size % 3 == 0) { "$name has unequal Mid sector counts" }
            for (orbit in mid.chunked(3)) for (sector in 1..2) {
                val (x, y, z) = orbit.first()
                val a = sector * 2 * Math.PI / 3
                check(orbit[sector] == listOf((x * cos(a) - z * sin(a)).roundToInt(), y,
                    (x * sin(a) + z * cos(a)).roundToInt())) { "$name is not a rounded 120-degree orbit: $orbit" }
            }
        }
    }
    val starTable = StringBuilder("# Spawn → nearest base star\n\nHorizontal Euclidean distance uses the shipped spawn position and fixed star anchor. PathFinder distances count cells including start; diagonals count as one (ADR-0322). All four candidates are searched; the nearest straight spot is also routed explicitly. Ratio = path to that spot / horizontal distance.\n\n| Spawn | Position x/y/z | Nearest straight spot | Straight | Path | Path / straight |\n|---|---|---|---:|---:|---:|\n")
    val fairness = linkedMapOf<String, MutableList<Int>>()
    fun measure(row: String, team: String, start: Triple<Int, Int, Int>, goals: List<Triple<Int, Int, Int>>): Int {
        check(finder.standable(start.first, start.second, start.third)) { "invalid fairness start $row $team $start" }
        check(goals.isNotEmpty()) { "no fairness goals $row $team" }
        val distances = goals.map { goal -> checkNotNull(walk("fairness $team $row", start, goal, .6)) }
        return distances.min().also { fairness.getOrPut(row) { mutableListOf() }.add(it) }
    }
    for (team in teams) {
        val spawn = cell(stage.spawnPoints.first { it.team?.value == team }.position)
        for ((name, values) in coordinates.getValue("ore").jsonObject) {
            val goals = values.jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.int } }
                .filter { hypot(it[0].toDouble(), it[2].toDouble()) <= 43 }
                .flatMap { (x, y, z) -> listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (dx, dz) -> Triple(x + dx, y, z + dz) } }
                .filter { finder.standable(it.first, it.second, it.third) }.distinct()
            measure("spawn → Mid $name", team, spawn, goals)
        }
        val spawns = stage.spawnPoints.filter { it.team?.value == team }
        val redSpawns = stage.spawnPoints.filter { it.team?.value == "red" }
        check(spawns.size == 4)
        for ((point, red) in spawns.zip(redSpawns)) {
            val r = cell(red.position)
            check(cell(point.position) == local(team, r.first, r.third + 108)) { "spawn layout differs for $team" }
            val p = cell(point.position)
            if (!baseline) check(plan.isSolid(p.first, p.second + 5, p.third)) { "spawn lacks guardhall roof: $team $p" }
        }
        val arcade = local(team, 7, -2)
        if (!baseline) check((65..68).all { !plan.isSolid(arcade.first, it, arcade.third) }) { "guardhall arcade blocked: $team" }
        val baseStars = coordinates.getValue("stars").jsonObject.filterKeys { it.startsWith("base-$team-") }
            .map { (name, value) -> name to value.jsonArray.map { it.jsonPrimitive.int }.let { Triple(it[0], it[1], it[2]) } }
        check(baseStars.size == 4)
        for ((index, point) in spawns.withIndex()) {
            val p = point.position
            val (nearest, goal) = baseStars.minBy { (_, g) -> hypot(p.x - g.first, p.z - g.third) }
            val straight = hypot(p.x - goal.first, p.z - goal.third)
            val path = measure("spawn#$index → nearest base star", team, cell(p), baseStars.map { it.second })
            val nearestPath = checkNotNull(walk("$team spawn#$index → $nearest", cell(p), goal))
            val ratio = nearestPath / straight
            starTable.append("| $team#$index | ${p.x}/${p.y}/${p.z} | $nearest | %.3f | $nearestPath | %.3f |\n".format(straight, ratio))
            check(finder.standable(cell(p).first, cell(p).second, cell(p).third)) { "unsafe spawn $team#$index" }
            if (!baseline) {
                check(straight in 20.0..25.0) { "$team#$index star straight distance $straight" }
                check(nearestPath in 20..25) { "$team#$index nearest straight star path $nearestPath" }
                check(path in 20..25) { "$team#$index star path distance $path" }
                check(ratio <= 1.4) { "$team#$index star detour ratio $ratio" }
            }
        }
        val own = stage.pillars.filter { it.team.value == team }.sortedBy { it.tier.ordinalValue }
        fun stances(p: com.astarworks.astera.core.domain.geometry.Vec3): List<Triple<Int, Int, Int>> {
            val (x, y, z) = cell(p)
            return listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (dx, dz) -> Triple(x + dx, y - 1, z + dz) }
        }
        for (pillar in own) measure("spawn → own T${pillar.tier.ordinalValue}", team, spawn, stances(pillar.position))
        val contest = local(team, 5, 15)
        val defenders = stage.spawnPoints.filter { it.team?.value == team }.map {
            checkNotNull(walk("fairness $team defender → contest", cell(it.position), contest))
        }
        measure("spawn → contest", team, spawn, listOf(contest))
        for ((gate, uv) in listOf("Mid" to (0 to 38), "left" to (-38 to 0), "right" to (38 to 0))) {
            val entry = local(team, uv.first, uv.second)
            val attack = measure("$gate entry → T1", team, entry, stances(own.first().position))
            val approach = measure("$gate entry → contest", team, entry, listOf(contest))
            if (!baseline) {
                check(defenders.max() < approach) { "$team defenders cannot contest $gate: $defenders vs $approach" }
                val defence = checkNotNull(walk("fairness $team defender → T1", spawn, stances(own.first().position).first()))
                check(defence < attack) { "$team cannot defend T1 before $gate entry" }
            }
        }
    }
    val table = StringBuilder("# Highmarch ${if (baseline) "before" else "after"} fairness\n\nShipped Highmarch content, production PathFinder, nearest reachable mining/chest-height stance, path cells including start; diagonals count as one. First spawn in each team's shipped spawn list. Entries: local (0,38), (-38,0), (38,0), first interior cell at the Mid/left/right gate. All candidate nodes and four mining stances are searched.\n\n| Route (cells) | Red | Blue | Yellow | Max/min |\n|---|---:|---:|---:|---:|\n")
    for ((name, values) in fairness) {
        val ratio = values.max().toDouble() / values.min()
        table.append("| $name | ${values.joinToString(" | ")} | %.3f |\n".format(ratio))
        if (!baseline && ratio > 1.10) { failures++; System.err.println("Unfair $name: $values ratio=$ratio") }
    }
    if (!baseline) {
        // Keep the ADR-0322 reference pacing within 10%, as well as cross-team parity.
        for ((row, target) in mapOf("spawn → own T1" to 20, "spawn → own T2" to 45,
            "spawn → own T3" to 32, "Mid entry → T1" to 42, "left entry → T1" to 49, "right entry → T1" to 38)) {
            check(fairness.getValue(row).all { kotlin.math.abs(it - target) <= target * .10 }) { "ADR-0322 pacing drift: $row ${fairness.getValue(row)}" }
        }
        check(fairness.getValue("spawn → own T2").all { it > 30 })
        check(fairness.getValue("spawn → own T3").all { it > 30 })
    }
    Files.writeString(reportPath.parent.resolve(if (baseline) "spawn-stars-before.md" else "spawn-stars-after.md"), starTable)
    Files.writeString(reportPath.parent.resolve(if (baseline) "before.md" else "after.md"), table)
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
            for ((dx, dz) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                val goal = Triple(p.first + dx, p.second - 1, p.third + dz)
                check(finder.standable(goal.first, goal.second, goal.third)) { "chest-height shrine stance obstructed: $goal" }
                walk("$team tier-${pillar.tier.ordinalValue} shrine stance $dx,$dz", start, goal)
            }
            for ((dx, dz) in listOf(0 to 5, 0 to -5, 5 to 0, -5 to 0)) {
                val goal = Triple(p.first + dx, p.second, p.third + dz)
                check(finder.standable(goal.first, goal.second, goal.third)) { "pillar approach is obstructed: $goal" }
                walk("$team tier-${pillar.tier.ordinalValue} approach $dx,$dz", start, goal)
            }
        }
        for ((name, value) in coordinates.getValue("climbs").jsonObject) {
            if (!name.startsWith(team) && name !in listOf("chapel", "chapel-bell-lookout", "east-crane", "south-crane") && !name.startsWith("outpost-")) continue
            val p = value.jsonArray.map { it.jsonPrimitive.int }
            val goal = Triple(p[0], p[1], p[2])
            if (!finder.standable(goal.first, goal.second, goal.third)) { failures++; System.err.println("$name has no standable goal $goal") }
            walk("$team spawn -> $name", start, goal)
        }
        for ((name, value) in coordinates.getValue("stars").jsonObject) {
            if (!name.startsWith("cache-$team") && !name.startsWith("flank-")) continue
            val p = value.jsonArray.map { it.jsonPrimitive.int }
            walk("$team spawn -> $name", start, Triple(p[0], p[1], p[2]), 2.0)
        }
    }
    for ((name, a, b) in listOf(
        Triple("red-east", Triple(39, 65, -108), Triple(85, 65, -50)),
        Triple("east-blue", Triple(85, 65, -50), Triple(94, 65, 15)),
        Triple("red-west", Triple(-39, 65, -108), Triple(-85, 65, -50)),
        Triple("west-yellow", Triple(-85, 65, -50), Triple(-94, 65, 15)),
        Triple("blue-south", Triple(94, 65, 93), Triple(0, 65, 112)),
        Triple("south-yellow", Triple(0, 65, 112), Triple(-94, 65, 93)),
    )) walk("ridge $name", a, b, 2.0)
    // All shipped ore cells are still air before OreNodeService writes them, and have a mining stance beside them.
    val red = cell(stage.spawnPoints.first { it.team?.value == "red" }.position)
    for ((i, p) in nodes.withIndex()) {
        val x = p.x.toInt(); val y = p.y.toInt(); val z = p.z.toInt()
        check(!plan.blocks.containsKey(ArenaBlockPos(x, y, z))) { "ore cell $i occupied before service placement" }
        val stances = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (dx, dz) -> Triple(x + dx, y, z + dz) }
        val goal = checkNotNull(stances.firstOrNull { finder.standable(it.first, it.second, it.third) }) { "ore $i has no mining stance" }
        walk("red spawn -> ore#$i", red, goal, 1.5)
    }
    walk("west channel -> bank stair", Triple(-64, 57, 0), Triple(-81, 65, 0))
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
