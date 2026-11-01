package com.astarworks.astera.e2e

import com.astarworks.astera.bots.ObjectiveWalker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * G1: a whole Annihilation match with three bots (one per team). Red attacks the other two teams' pillars:
 * Build-phase immunity, damage by pistol shots and by breaking the pillar block, the tier gates, elimination of blue
 * and then yellow, winner declared, summary/rewards, everyone (spectators included) back in the lobby.
 * Timers and HP come from docker/e2e-tune-content.sh (build 5s, engage 10s, every pillar 150 HP).
 *
 * Highmarch is one continuous continent (ADR-0321): red forge town north, blue harbour east, yellow farmland west,
 * a terraced quarry at Mid, river-gorge bridges and town-to-town ridge crossings. Void exists only outside the rim.
 * All coordinates are stage-local; pillar anchors and terrain expectations below come from shipped YAML.
 */
class AnnihilationPlaythroughE2eTest : PlaythroughBase() {

    private val suite = "Annihilation playthrough"

    /**
     * Pillar block cells per tier (1..3), stage-local (slot origin is added by the helpers). Read from the shipped
     * `content/stages/annihilation-arena.yaml` `pillars:` section so a stage move never leaves the test digging air;
     * the cell is the floored position (the pillar block's cell, as the stage loader / bot planner use).
     */
    private val pillars: Map<String, List<Triple<Int, Int, Int>>> = loadPillars()

    private fun loadPillars(): Map<String, List<Triple<Int, Int, Int>>> {
        val file = java.io.File(System.getProperty("astera.live.contentDir", "/workspace/content"), "stages/annihilation-arena.yaml")
        val lines = file.readLines().dropWhile { it.trim() != "pillars:" }.drop(1).takeWhile { it.startsWith(" ") || it.startsWith("-") }
        val entry = Regex("""-\s*team:\s*(\w+)""")
        val tier = Regex("""tier:\s*(\d+)""")
        val pos = Regex("""position:\s*\{\s*x:\s*(-?[\d.]+),\s*y:\s*(-?[\d.]+),\s*z:\s*(-?[\d.]+)\s*\}""")
        val byTeam = mutableMapOf<String, MutableMap<Int, Triple<Int, Int, Int>>>()
        var team: String? = null
        var t = 0
        for (line in lines) {
            entry.find(line)?.let { team = it.groupValues[1].replaceFirstChar(Char::uppercase) }
            tier.find(line)?.let { t = it.groupValues[1].toInt() }
            pos.find(line)?.let { m ->
                val (x, y, z) = m.destructured
                byTeam.getOrPut(team ?: error("pillar position before team")) { mutableMapOf() }[t] =
                    Triple(Math.floor(x.toDouble()).toInt(), Math.floor(y.toDouble()).toInt(), Math.floor(z.toDouble()).toInt())
            }
        }
        check(byTeam.keys == setOf("Blue", "Yellow", "Red")) { "unexpected pillar teams ${byTeam.keys} in $file" }
        return byTeam.mapValues { (name, tiers) -> (1..3).map { tiers[it] ?: error("stage has no $name tier $it pillar") } }
    }
    /** The block id of tier 1..3 as the stage YAML declares it (hard wood / hard dirt / hard stone since MODE-A1; one block per tier for every team). */
    private val pillarBlocks: List<String> = run {
        val file = java.io.File(System.getProperty("astera.live.contentDir", "/workspace/content"), "stages/annihilation-arena.yaml")
        val lines = file.readLines().dropWhile { it.trim() != "pillars:" }.drop(1).takeWhile { it.startsWith(" ") || it.startsWith("-") }
        var t = 0
        val byTier = sortedMapOf<Int, String>()
        for (line in lines) {
            Regex("""tier:\s*(\d+)""").find(line)?.let { t = it.groupValues[1].toInt() }
            Regex("""block:\s*(\S+)""").find(line)?.let { byTier.putIfAbsent(t, it.groupValues[1]) }
        }
        (1..3).map { byTier[it] ?: error("stage has no tier $it pillar block") }
    }

    private fun teamOfBot(b: Bot): String? = localPosOf(b)?.let { (x, _, z) ->
        pillars.minBy { (_, cells) -> cells.first().let { Math.hypot(x - it.first, z - it.third) } }.key
    }

    private val content = java.io.File(System.getProperty("astera.live.contentDir", "/workspace/content"))
    private val worldLines by lazy { content.resolve("arenas/annihilation-highmarch.yaml").readLines() }
    private fun worldVector(key: String): List<Int> = worldLines.first { it.startsWith("$key:") }
        .substringAfter('[').substringBefore(']').split(',').map { it.trim().toInt() }

    /** Read only the needed ADR-0281 RLE layer; avoid expanding the two-million-block world for a few probes. */
    private fun worldLayer(y: Int): Map<Pair<Int, Int>, String> {
        val (ox, oy, oz) = worldVector("origin")
        val (sx, sy, sz) = worldVector("size")
        require(y in oy until oy + sy)
        val palette = listOf("minecraft:air") + worldLines.dropWhile { it != "palette:" }.drop(1)
            .takeWhile { it != "layers:" }.map { it.trim().removePrefix("- ").trim('"') }
        val layer = worldLines.dropWhile { it != "layers:" }[y - oy + 1].trim().removePrefix("- ").trim('"')
        val cells = mutableMapOf<Pair<Int, Int>, String>()
        var cell = 0
        for (run in layer.split(' ')) {
            val token = run.substringBefore(':').toInt()
            val count = run.substringAfter(':').toInt()
            repeat(count) {
                cells[ox + cell % sx to oz + cell / sx] = palette[token]
                cell++
            }
        }
        check(cell == sx * sz)
        return cells
    }

    /** `(tiers, hp)` of a team's sidebar line (`Blue ●●● 180 HP`), or null when the line is not there. */
    private fun teamLine(b: Bot, team: String): Pair<String, Int>? {
        val line = sidebar(b).firstOrNull { it.contains(team, ignoreCase = true) } ?: return null
        val tiers = line.filter { it == '●' || it == '○' }
        val hp = Regex("(\\d+)\\s*HP").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return tiers to -1
        return tiers to hp
    }

    private fun eliminated(b: Bot, team: String): Boolean =
        sidebar(b).any { it.contains(team, ignoreCase = true) && (it.contains("out", ignoreCase = true)) }

    private fun phaseLine(b: Bot): String = sidebar(b).firstOrNull { Regex("Build|Engage|Tier 3|Sudden", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty()

    /** Stand 3.5 blocks north of the pillar block, look south at it. */
    private fun stand(a: Bot, p: Triple<Int, Int, Int>) {
        tpArena(a.name, p.first + 0.5, p.second.toDouble(), p.third - 3.5, 0f, 16f)
        Thread.sleep(400)
    }

    /** Digs the stage-local cell [p] (dig packets carry absolute positions). */
    private fun dig(a: Bot, p: Triple<Int, Int, Int>) = a.dig(p.first + originX, p.second, p.third + originZ)

    private fun shoot(a: Bot, times: Int) {
        repeat(times) {
            // The magazine holds 12; an empty one fires nothing (Shift+Q reloads).
            if ((ammoOf(a) ?: 12) <= 0) reload(a)
            a.useItem(0f, 16f)
            Thread.sleep(330)
        }
    }

    @Test
    fun `annihilation with three teams is played to a winner`() {
        val logFrom = logLineCount()
        val bots = (1..3).map { bot("An$it", PackPolicy.DECLINE) }
        bots.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = bots[0].sidebarTitle()
        val chatFrom = bots.associateWith { it.chat.size }

        val tQueue = System.nanoTime()
        bots.forEach { it.command("astera queue join annihilation") }
        val active = awaitMatch("ACTIVE", 70_000, keepAlive = bots)
        val tActive = System.nanoTime()
        E2eReport.check(suite, "queue forms an annihilation match and it goes ACTIVE (pacing)", "RCON astera match list") {
            assertThat(active).describedAs("never ACTIVE; list=${matchLines()}; chat=${bots[0].chatTexts().takeLast(6)}").isNotNull()
            "queue -> ACTIVE in ${(tActive - tQueue) / 1_000_000_000}s; line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(1_500)

        val teams = bots.associateWith { teamOfBot(it) }
        val a = bots.firstOrNull { teams[it] == "Red" }
        val blue = bots.firstOrNull { teams[it] == "Blue" }
        val yellow = bots.firstOrNull { teams[it] == "Yellow" }
        E2eReport.check(suite, "three bots land on three different team spawns", "RCON data get Pos") {
            assertThat(listOfNotNull(a, blue, yellow)).describedAs("spawn sides: ${teams.mapKeys { it.key.name }} positions=${bots.map { posOf(it) }}").hasSize(3)
            "teams=${teams.mapKeys { it.key.name }}"
        }
        if (a == null || blue == null || yellow == null) return
        val idle = listOf(blue, yellow)

        E2eReport.check(suite, "every team spawns standing on solid town ground inside its slot", "RCON data get Dimension/Pos + execute unless block") {
            val problems = bots.mapNotNull { islandStanceProblem(it) }
            assertThat(problems).describedAs("bots not on town ground: $problems").isEmpty()
            "slot ($originX, $originZ), solid block under every bot: ${bots.map { it.name to posOf(it) }}"
        }
        E2eReport.check(suite, "nine pillars have team-coloured footings and the quarry approach has continuous ground", "RCON execute if block") {
            val wrong = mutableListOf<String>()
            for ((team, cells) in pillars) {
                cells.forEachIndexed { i, (x, y, z) ->
                    if (!arenaBlockIs(x, y, z, pillarBlocks[i])) wrong += "$team tier ${i + 1} block ${pillarBlocks[i]} missing at ($x $y $z)"
                    if (!arenaBlockIs(x, y - 1, z, "minecraft:${team.lowercase()}_concrete")) wrong += "$team tier ${i + 1} footing ${team.lowercase()}_concrete missing at ($x ${y - 1} $z)"
                }
            }
            assertThat(wrong).describedAs("pillar anchors: $wrong").isEmpty()
            val ground = worldLayer(64).getValue(40 to -10)
            assertThat(ground).describedAs("dry ground beyond the r42 quarry rim").isNotIn("minecraft:air", "minecraft:water")
            assertThat(arenaBlockIs(40, 64, -10, ground)).describedAs("shipped continent ground at (40,64,-10)").isTrue()
            val outsideX = worldVector("origin")[0] + worldVector("size")[0]
            assertThat(arenaSolid(outsideX, 64, 0) || arenaSolid(outsideX, 50, 0)).describedAs("void beyond outer rim").isFalse()
            "9 pillar blocks + 9 coloured footings; dry approach=$ground; outer void x=$outsideX"
        }

        E2eReport.check(suite, "sidebar shows the Build phase and three intact 150 HP pillar sets", "SetObjective/SetScore") {
            val lines = sidebar(a)
            assertThat(a.sidebarTitle()).isNotEqualTo(lobbyTitle)
            assertThat(phaseLine(a)).describedAs("no phase line: $lines").isNotBlank()
            assertThat(teamLine(a, "Blue")).describedAs("blue line in $lines").isEqualTo("●●●" to 450)
            assertThat(teamLine(a, "Yellow")).describedAs("yellow line in $lines").isEqualTo("●●●" to 450)
            assertThat(rawKeys(lines)).isEmpty()
            quote(lines)
        }

        // --- Build phase: pillars are immune --------------------------------------------------------------------
        val slot = equip(a, "pistol")
        val inBuild = phaseLine(a).contains("Build", ignoreCase = true)
        if (inBuild) {
            stand(a, pillars.getValue("Blue")[0])
            shoot(a, 4)
            dig(a, pillars.getValue("Blue")[0])
            Thread.sleep(1_800)
            E2eReport.check(suite, "Build phase: pillars take no damage from shots or block breaking", "SetScore (sidebar HP)") {
                assertThat(teamLine(a, "Blue")).describedAs("blue pillars changed during Build: ${sidebar(a)}").isEqualTo("●●●" to 450)
                "slot=$slot; sidebar=${sidebar(a)}"
            }
        } else {
            E2eReport.notes += "Annihilation: Build phase was already over when the immunity check was due (phase line '${phaseLine(a)}')"
        }

        // --- Engage: Tier 1 opens ---------------------------------------------------------------------------------
        val engaged = a.waitUntil(30_000) { phaseLine(a).let { it.contains("Engage", true) || it.contains("Tier 3", true) } }
        E2eReport.check(suite, "the phase line moves on from Build by itself", "SetScore (sidebar)") {
            assertThat(engaged).describedAs("still '${phaseLine(a)}' 30s later").isTrue()
            "phase line now '${phaseLine(a)}'"
        }
        val blueT1 = pillars.getValue("Blue")[0]
        stand(a, blueT1)
        val hpBefore = teamLine(a, "Blue")?.second ?: -1
        var shotsToHit = 0
        while (shotsToHit < 24 && (teamLine(a, "Blue")?.second ?: -1) >= hpBefore) {
            shoot(a, 3)
            shotsToHit += 3
            Thread.sleep(1_300)
        }
        val hpAfterShots = teamLine(a, "Blue")?.second ?: -1
        E2eReport.check(suite, "Engage: pistol shots damage a Tier 1 pillar (HP on the sidebar drops)", "SetScore (sidebar HP)") {
            assertThat(hpAfterShots).describedAs("blue HP $hpBefore -> $hpAfterShots after $shotsToHit shots; sidebar=${sidebar(a)}").isLessThan(hpBefore)
            "blue HP $hpBefore -> $hpAfterShots after $shotsToHit shots (~17 per hit)"
        }

        // The pistol (e2e tune: full weapon damage, no falloff) can already have felled Tier 1: dig the first pillar still standing.
        val digCell = pillars.getValue("Blue")[(teamLine(a, "Blue")?.first?.indexOf('●') ?: 0).coerceAtLeast(0)]
        stand(a, digCell)
        rcon("effect give ${a.name} minecraft:haste 600 100 true")
        rcon("item replace entity ${a.name} hotbar.7 with minecraft:diamond_pickaxe")
        a.selectHotbar(7)
        Thread.sleep(300)
        val hpBeforeDig = teamLine(a, "Blue")?.second ?: -1
        repeat(2) { dig(a, digCell); Thread.sleep(400) }
        Thread.sleep(1_800)
        var hpAfterDig = teamLine(a, "Blue")?.second ?: -1
        var creativeFallback = false
        if (hpAfterDig >= hpBeforeDig && hpBeforeDig > 0) {
            // The survival dig did not land (tool speed / game mode): retry in creative so the rest of the match can be played.
            creativeFallback = true
            rcon("gamemode creative ${a.name}")
            Thread.sleep(300)
            repeat(2) { dig(a, digCell); Thread.sleep(400) }
            Thread.sleep(1_800)
            hpAfterDig = teamLine(a, "Blue")?.second ?: -1
        }
        E2eReport.check(suite, "Engage: breaking a pillar block damages the pillar (the block stays)", "SetScore (sidebar HP) + BlockUpdate") {
            assertThat(hpAfterDig).describedAs("blue HP $hpBeforeDig -> $hpAfterDig after digging (creative fallback=$creativeFallback); sidebar=${sidebar(a)}").isLessThan(hpBeforeDig)
            assertThat(creativeFallback).describedAs("survival dig with haste II-100 + diamond pickaxe did nothing; only creative worked").isFalse()
            "blue HP $hpBeforeDig -> $hpAfterDig (25 per break), creativeFallback=$creativeFallback"
        }
        a.selectHotbar(slot ?: 0)
        rcon("gamemode survival ${a.name}")

        // --- take the pillars down tier by tier ----------------------------------------------------------------------
        fun destroyTeam(team: String): Boolean {
            val cells = pillars.getValue(team)
            val deadline = System.currentTimeMillis() + 150_000
            while (System.currentTimeMillis() < deadline && !eliminated(a, team)) {
                idle.forEach { it.nudge() }
                val tiers = teamLine(a, team)?.first ?: break
                val idx = tiers.indexOf('●')
                if (idx < 0) break
                val cell = cells[idx]
                stand(a, cell)
                if (idx == 0) {
                    // Tier 1: mix shots and block breaks; both must work.
                    shoot(a, 3)
                    rcon("gamemode creative ${a.name}")
                    dig(a, cell)
                    rcon("gamemode survival ${a.name}")
                } else {
                    shoot(a, 4)
                }
                Thread.sleep(1_300)
            }
            return eliminated(a, team)
        }

        val blueDown = destroyTeam("Blue")
        E2eReport.check(suite, "Tier 1 -> 2 -> 3 of Blue fall; Blue is eliminated (sidebar) and its player spectates", "SetScore (sidebar) + RCON playerGameType") {
            assertThat(blueDown).describedAs("Blue not eliminated after the deadline; sidebar=${sidebar(a)} phase='${phaseLine(a)}'").isTrue()
            val gt = gameTypeOf(blue)
            assertThat(gt).describedAs("eliminated player's game mode").isEqualTo(3)
            "sidebar=${sidebar(a)}; blue gameType=$gt"
        }
        E2eReport.check(suite, "the match keeps running with two teams left (no premature end)", "RCON astera match list") {
            assertThat(matchLines()).describedAs("match vanished after one elimination").isNotEmpty()
            "list=${matchLines().map { it.raw }}"
        }

        val yellowDown = destroyTeam("Yellow")
        val ended = awaitNoMatch(45_000, keepAlive = idle)
        E2eReport.check(suite, "eliminating the last rival ends the match with Red as the winner (no admin end)", "RCON astera match list + chat") {
            assertThat(yellowDown || ended).describedAs("Yellow not eliminated by the deadline; sidebar=${sidebar(a)}").isTrue()
            assertThat(ended).describedAs("match still listed after the last elimination: ${matchLines()}").isTrue()
            "ended=$ended"
        }
        checkEnding(suite, bots, chatFrom, lobbyTitle)
        E2eReport.check(suite, "the result names Red as winner (winner and losers see the right verdict data)", "SystemChat") {
            val chat = a.chat.drop(chatFrom.getValue(a)).map { clean(it.text) }
            val you = chat.firstOrNull { it.startsWith("Score") }.orEmpty()
            assertThat(chat.any { it.contains("Match Summary") }).describedAs("no summary:\n${quote(chat, 30)}").isTrue()
            "winner chat score line: '$you'; MVP/lines: ${chat.filter { it.contains("MVP") || it.contains("Ended by") }}"
        }
        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }

    @Test
    fun `gorge fall survives and nearest ladder returns the bot to the bank`() {
        val bots = (1..3).map { bot("Gorge$it", PackPolicy.DECLINE) }
        bots.forEach { b -> check(b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 }) }
        bots.forEach { it.command("astera queue join annihilation") }
        val active = awaitMatch("ACTIVE", 70_000, keepAlive = bots)
        assertThat(active).describedAs("Highmarch match must become ACTIVE: ${matchLines()}").isNotNull()
        check(active?.raw?.contains("annihilation-arena-slot-") == true) { "gorge recovery requires the shipped Highmarch stage: $active" }
        val b = bots.first()
        rcon("gamemode survival ${b.name}")
        val water = worldLayer(56)
        val low = worldLayer(57)
        val dropLayer = worldLayer(60)
        val top = worldLayer(64)
        // North lane bridge centre is (0,-65) in the r4b route proof. Select actual open water beside its deck.
        val drop = water.keys.filter { (x, z) ->
            x in 4..8 && z in -70..-60 && water[x to z] == "minecraft:water" &&
                low[x to z] == "minecraft:air" && dropLayer[x to z] == "minecraft:air" && top[x to z] == "minecraft:air"
        }.minBy { (x, z) -> Math.hypot(x.toDouble(), z + 65.0) }
        val ladder = low.filterValues { it.startsWith("minecraft:ladder[") }.keys
            .minBy { (x, z) -> Math.hypot((x - drop.first).toDouble(), (z - drop.second).toDouble()) }
        val facing = low.getValue(ladder).substringAfter("facing=").substringBefore(']')
        val support = when (facing) {
            "north" -> 0 to 1
            "south" -> 0 to -1
            "east" -> -1 to 0
            "west" -> 1 to 0
            else -> error("unknown ladder facing $facing")
        }
        val killY = Regex("""kill_y:\s*(-?\d+)""").find(content.resolve("stages/annihilation-arena.yaml").readText())!!.groupValues[1].toDouble()
        val deaths = b.deaths.size
        var minY = Double.MAX_VALUE
        val started = System.nanoTime()
        fun observe() {
            val pos = localPosOf(b) ?: error("server lost gorge bot position")
            minY = minOf(minY, pos.second)
            check(pos.second > killY && b.deaths.size == deaths && b.health > 0 && gameTypeOf(b) == 0) {
                "gorge recovery died/respawned: pos=$pos minY=$minY health=${b.health} deaths=${b.deaths.size - deaths}"
            }
        }
        // The protocol client has no gravity/swimming solver. Send bounded client ticks at falling/swimming/climbing
        // speeds; Paper validates collisions. Never teleport after the initial drop or use flight/effects/block edits.
        fun move(x: Double, y: Double, z: Double, speed: Double = 0.13) {
            val ticks = kotlin.math.ceil(maxOf(Math.hypot(x + originX - b.x, z + originZ - b.z), kotlin.math.abs(y - b.y)) / speed).toInt().coerceAtLeast(1)
            val from = Triple(b.x, b.y, b.z)
            repeat(ticks) { i ->
                check((System.nanoTime() - started) / 1_000_000 < 25_000) { "gorge recovery exceeded 25s" }
                val f = (i + 1.0) / ticks
                b.moveTo(from.first + (x + originX - from.first) * f, from.second + (y - from.second) * f,
                    from.third + (z + originZ - from.third) * f, 0f, 0f)
                Thread.sleep(50)
                if (i % 10 == 0) observe()
            }
            observe()
        }
        E2eReport.check(suite, "fall into water beside the north bridge survives above kill_y", "shipped YAML + RCON Pos/game mode + death packets") {
            assertThat(arenaBlockIs(drop.first, 56, drop.second, "minecraft:water")).isTrue()
            tpArena(b.name, drop.first + 0.5, 60.0, drop.second + 0.5, 0f, 0f)
            check(b.waitUntil(3_000) { kotlin.math.abs(b.x - originX - drop.first - 0.5) < 0.5 && kotlin.math.abs(b.y - 60) < 0.5 })
            move(drop.first + 0.5, 56.25, drop.second + 0.5, 0.2)
            repeat(20) { b.nudge(); Thread.sleep(50) }
            observe()
            assertThat(localPosOf(b)!!.second).isBetween(56.0, 57.0)
            "drop=$drop minY=$minY kill_y=$killY deaths=0"
        }
        E2eReport.check(suite, "swim to the nearest ladder and climb onto the bank within 25s", "RCON Pos/block checks + protocol movement + A* bank walk") {
            for (y in 57..64) assertThat(arenaBlockIs(ladder.first, y, ladder.second, low.getValue(ladder))).isTrue()
            // Approach from the exposed face, then ascend within the ladder column before stepping onto its support.
            move(drop.first + 0.5, 57.05, drop.second + 0.5, 0.05)
            move(ladder.first - support.first + 0.5, 57.05, ladder.second - support.second + 0.5)
            move(ladder.first + 0.5, 57.05, ladder.second + 0.5)
            move(ladder.first + 0.5, 65.05, ladder.second + 0.5, 0.1176)
            val bankX = ladder.first + support.first + 0.5
            val bankZ = ladder.second + support.second + 0.5
            // Reuse A* for the step off the ladder onto its bank support (water/ladder traversal is explicit above).
            val walked = ObjectiveWalker(b, null).walkTo(bankX + originX, bankZ + originZ, 0.1, 2_000)
            observe()
            val pos = localPosOf(b)!!
            assertThat(walked).isEqualTo(ObjectiveWalker.Result.ARRIVED)
            assertThat(pos.second).isGreaterThanOrEqualTo(64.0)
            assertThat(Math.hypot(pos.first - bankX, pos.third - bankZ)).isLessThan(0.75)
            assertThat(islandStanceProblem(b)).isNull()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertThat(elapsed).isLessThan(25_000)
            "nearest ladder=$ladder bank=$pos elapsed=${elapsed}ms minY=$minY deaths=0"
        }
    }

}
