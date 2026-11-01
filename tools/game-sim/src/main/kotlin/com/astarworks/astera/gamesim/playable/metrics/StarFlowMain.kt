package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.match.application.service.StarBudget
import com.astarworks.astera.match.application.service.StarSupplyClock
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.star.StarSource
import kotlin.math.hypot

/**
 * Star-flow probe: `./gradlew -q :tools:game-sim:starFlow -Pseeds=101,...,110 -Psize=6 [-PmaxTicks=24000]`. Per phase (P1..P5) and
 * source, over all seeds (values are per-match means): budget tokens available (integral of the source's bucket refill rate), tokens
 * requested, stars minted, picked up by team (red/blue/yellow), despawned (gone from the ground without a pickup), and still on the
 * ground at the end of the match. Additive diagnostic; reads only sim events, the budget stats and the sim's star drops.
 *
 * Sources: spot (flank/base timer pops), opening (a base lane's first-tick pops, outside the buckets), wave, mining, blocks, cache,
 * bounty (these four are credited straight to the player, so minted = picked up), death (a kill burst: ground stars redistributed
 * from the victim, not minted). A ground star is attributed to its source by what happened on the tick it appeared (kill nearby =
 * death, a wave supply = wave, a lane's first supply tick = opening, else spot).
 */
private val NAMES = listOf("spot", "opening", "mining", "blocks", "cache", "wave", "bounty", "death")
private const val PHASES = 5

private class Cell {
    var budget = 0.0; var requested = 0.0; var minted = 0.0; var gone = 0.0; var ground = 0.0
    val picked = HashMap<String, Double>()
}

fun main(args: Array<String>) {
    val seeds = args.getOrElse(0) { "101" }.split(',').mapNotNull { it.trim().toLongOrNull() }
    val size = args.getOrNull(1)?.toIntOrNull() ?: 6
    val maxTicks = args.getOrNull(2)?.toIntOrNull() ?: 24000
    val cells = Array(PHASES) { HashMap<String, Cell>().also { m -> NAMES.forEach { n -> m[n] = Cell() } } }
    val phaseMinutes = DoubleArray(PHASES)
    val phaseReached = IntArray(PHASES)
    val teams = ArrayList<String>()
    var runs = 0
    for (seed in seeds) {
        MatchDriver.boot(MatchConfig(seed = seed, botsPerTeam = size)).use { d ->
            val teamOf = d.seats.associate { it.entity.id to it.team }
            teamOf.values.distinct().forEach { if (it !in teams) teams += it }
            val budget = d.sim.services.getOrThrow<StarBudget>()
            val gens = d.sim.services.getOrThrow<com.astarworks.astera.match.application.port.outbound.IGeneratorRepository>().listForMatch(d.matchId)
            val clock = d.sim.services.getOrThrow<StarSupplyClock>()
            var started: Long? = null
            var cursor = 0
            val phaseStart = LongArray(PHASES) { -1L }
            fun phaseAt(tick: Long): Int { var p = 0; for (i in 1 until PHASES) if (phaseStart[i] in 0..tick) p = i; return p }
            val sourceOf = HashMap<String, String>() // ground handle -> source name
            val laneSeen = HashSet<String>()
            val kills = ArrayList<Pair<Vec3, Long>>()
            var prev = HashMap<String, Int>()
            val lastStats = HashMap<String, Long>()
            var endTick = 0L
            var firstCache = -1L
            var lastGround: Map<String, Int> = emptyMap()
            var sourceSnapshot: Map<String, String> = emptyMap()
            d.onTick { tick ->
                if (d.ended) return@onTick
                val mid = d.matchId
                if (started == null) started = d.sim.events.filterIsInstance<MatchStarted>().firstOrNull()?.atTick
                val s0 = started ?: return@onTick
                val fresh = d.sim.events.subList(cursor, d.sim.events.size).toList()
                cursor = d.sim.events.size
                phaseStart[0] = s0
                for (e in fresh) if (e is AnnihilationPhaseTransitioned && e.matchId == mid) { val i = e.newPhase.number - 1; if (i in 1 until PHASES) phaseStart[i] = e.atTick }
                val ph = phaseAt(tick)
                phaseReached[ph] = maxOf(phaseReached[ph], 1)
                // budget tokens: integral of each source's refill rate (the wave opens with phase 3)
                val sec = (tick - s0) / 20.0
                val econ = clock.economyOf(mid)
                val n = budget.alive(mid)
                val p3 = if (phaseStart[2] >= 0) (phaseStart[2] - s0) / 20.0 else Double.MAX_VALUE
                for (src in StarSource.entries) {
                    val opens = { x: StarSource -> if (x == StarSource.WAVE) p3 else 0.0 }
                    cells[ph].getValue(src.key).budget += econ.sourceRatePerSecond(src, sec, n, opens) * (if (src.perPlayer) n else 1) / 20.0
                }
                for (e in fresh) if (e is PlayerKilled && e.killer != e.victim) d.sim.world.find(e.victim)?.position?.let { kills += it to tick }
                // minted / picked up straight to the player, and supply events
                var lastSupplied: StarSupplied? = null
                val groundPick = ArrayList<StarsCollected>()
                val suppliedNow = ArrayList<StarSupplied>()
                for (e in fresh) {
                    if (e is StarSupplied && e.matchId == mid) {
                        lastSupplied = e
                        val gid = e.generatorId.value
                        if (gid.startsWith("star-source-")) continue
                        suppliedNow += e
                        continue
                    }
                    if (e !is StarsCollected || e.matchId != mid) continue
                    val direct = lastSupplied?.takeIf { it.amount == e.amount && it.generatorId.value.startsWith("star-source-") }
                    lastSupplied = null
                    if (direct == null) { groundPick += e; continue }
                    val name = direct.generatorId.value.removePrefix("star-source-")
                    if (name == "cache" && firstCache < 0) firstCache = tick - s0
                    val c = cells[ph].getValue(name)
                    c.minted += e.amount
                    c.picked.merge(teamOf[e.playerId] ?: "?", e.amount.toDouble(), Double::plus)
                }
                // supply events of ground sources: wave, opening (a lane's first supply tick), spot
                val laneFirst = HashSet<String>()
                for (e in suppliedNow) {
                    val gid = e.generatorId.value
                    val isWave = gid.contains("wave")
                    val key = "lane:$gid"
                    val opening = !isWave && e.team != null && (key !in laneSeen)
                    if (opening) laneFirst += gid
                    val name = if (isWave) "wave" else if (opening || gid in laneFirst) "opening" else "spot"
                    cells[ph].getValue(name).minted += e.amount
                }
                laneFirst.forEach { laneSeen += "lane:$it" }
                // ground drops: appeared / vanished
                val now = HashMap<String, Int>()
                val gnow = d.sim.starDrops.lying()
                val waveNow = suppliedNow.any { it.generatorId.value.contains("wave") }
                for (drop in gnow) {
                    now[drop.handle] = drop.amount
                    if (drop.handle in prev) continue
                    val death = kills.lastOrNull { tick - it.second <= 2 && hypot(it.first.x - drop.position.x, it.first.z - drop.position.z) <= 4.0 }
                    val name = when {
                        death != null -> "death"
                        nearestIsWave(gens, drop.position) -> "wave"
                        laneFirst.isNotEmpty() && suppliedNow.any { it.generatorId.value in laneFirst } -> "opening"
                        else -> "spot"
                    }
                    sourceOf[drop.handle] = name
                    if (name == "death") cells[ph].getValue("death").minted += drop.amount
                }
                val gone = prev.keys.filter { it !in now }.toMutableList()
                for (e in groundPick) {
                    val h = gone.firstOrNull { prev[it] == e.amount } ?: continue
                    gone.remove(h)
                    cells[ph].getValue(sourceOf[h] ?: "spot").picked.merge(teamOf[e.playerId] ?: "?", e.amount.toDouble(), Double::plus)
                }
                for (h in gone) cells[ph].getValue(sourceOf[h] ?: "spot").gone += prev.getValue(h)
                // requested tokens per source (budget stats delta)
                for (src in StarSource.entries) {
                    val v = budget.stats.get(mid, "requested:${src.key}")
                    cells[ph].getValue(src.key).requested += (v - (lastStats[src.key] ?: 0L))
                    lastStats[src.key] = v
                }
                prev = now
                lastGround = now
                endTick = tick
                sourceSnapshot = HashMap(sourceOf)
            }
            d.stepUntil(maxTicks + 1200) { d.ended || (started?.let { d.tick - it > maxTicks } ?: false) }
            val s0 = started ?: 0L
            for (i in 0 until PHASES) {
                val a = if (phaseStart[i] >= 0) phaseStart[i] else continue
                val b = if (i + 1 < PHASES && phaseStart[i + 1] >= 0) phaseStart[i + 1] else endTick
                phaseMinutes[i] += (b - a).coerceAtLeast(0) / 1200.0
            }
            val endPh = phaseAt(endTick)
            for ((h, amount) in lastGround) cells[endPh].getValue(sourceSnapshot[h] ?: "spot").ground += amount
            runs++
            System.err.println("firstCache seed=$seed s=${if (firstCache < 0) "never" else "%.1f".format(firstCache / 20.0)}")
            System.err.println("run seed=$seed minutes=%.1f players=${teamOf.size} phases=${phaseStart.map { if (it < 0) "-" else "%.1f".format((it - s0) / 1200.0) }}".format((endTick - s0) / 1200.0))
        }
    }
    val teamList = teams.sorted()
    println("starFlow: ${size}v${size}v${size} annihilation-arena, seeds ${seeds.joinToString(",")}, maxTicks=$maxTicks; per-match means over $runs runs")
    for (p in 0 until PHASES) {
        val reached = phaseMinutes[p] > 0
        println("\nP${p + 1} (mean ${"%.1f".format(phaseMinutes[p] / runs)} min per match, ${if (reached) "reached" else "never reached"})")
        if (!reached) continue
        println("| source | budget tokens | requested | minted | picked ${teamList.joinToString("/")} | picked total | despawned | on ground at end |")
        println("|---|---:|---:|---:|---|---:|---:|---:|")
        var tb = 0.0; var tr = 0.0; var tm = 0.0; var tp = 0.0; var tg = 0.0; var te = 0.0
        for (name in NAMES) {
            val c = cells[p].getValue(name)
            val pk = teamList.map { c.picked[it] ?: 0.0 }
            val pt = c.picked.values.sum()
            val deathOrOpen = name == "death" || name == "opening"
            println("| $name | ${if (deathOrOpen) "-" else f(c.budget / runs)} | ${if (deathOrOpen) "-" else f(c.requested / runs)} | ${f(c.minted / runs)} | ${pk.joinToString("/") { f(it / runs) }} | ${f(pt / runs)} | ${f(c.gone / runs)} | ${f(c.ground / runs)} |")
            tb += if (deathOrOpen) 0.0 else c.budget; tr += if (deathOrOpen) 0.0 else c.requested
            if (name != "death") tm += c.minted
            tp += pt; tg += c.gone; te += c.ground
        }
        println("| total (death excluded from minted) | ${f(tb / runs)} | ${f(tr / runs)} | ${f(tm / runs)} | | ${f(tp / runs)} | ${f(tg / runs)} | ${f(te / runs)} |")
    }
}

private fun f(v: Double) = "%.1f".format(v)

/** A ground star belongs to the wave when the nearest generator (position or candidate spot) is the Mid wave. */
private fun nearestIsWave(gens: List<com.astarworks.astera.match.domain.generator.StarGenerator>, at: Vec3): Boolean {
    var best = Double.MAX_VALUE; var wave = false
    for (g in gens) for (p in listOf(g.position) + g.spots) {
        val dist = hypot(p.x - at.x, p.z - at.z)
        if (dist < best) { best = dist; wave = g.wave != null }
    }
    return wave
}
