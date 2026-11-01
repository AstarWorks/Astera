package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.MatchPhaseSnapshot
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.match.application.service.StarActionService
import com.astarworks.astera.match.application.service.StarBudget
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.star.StarEconomy
import com.astarworks.astera.match.domain.star.StarSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The star economy of the annihilation-arena (ADR-0318) on the real shipped content: a budget of
 * `per_player_minute(t) x N_alive`, drawn by timer generators (candidate spots, rotation), mining, block breaks, exploration
 * caches, Mid waves sized by N, and a kill/bounty rule. Played for 20 game minutes with 3, 6 and 12 players who mine,
 * break blocks, walk to caches, pick up what lies on the map and kill now and then. Prints the table that goes into the ADR
 * and asserts (with the real phase lengths 6/8/8/6/5 min): the Mid wave idles in P1/P2 and opens at P3 (first wave one warn after
 * the P3 start, ADR-0318 follow-up), its budget share moves to the open sources, the per-phase total stays within 20% of the budget,
 * a match without a phase clock keeps the ungated wave, per-player income stays within 20% of the formula budget at every N, timer generators stay a minor source
 * and never pop on the same spot twice in a row, waves grow with N, and a kill on the leader pays more than one on a trailing player.
 */
class StarEconomyScenarioTest {

    private val config = AnnihilationConfig()
    /** Phase boundaries in match minutes: P1 0-6, P2 6-14, P3 14-22, P4 22-28, P5 28-33. */
    private val phaseStarts = listOf(0, 6, 14, 22, 28, 33)
    private val phaseNames = listOf("P1 build", "P2 engage", "P3 center", "P4 escalation", "P5 sudden death")
    private val warnSeconds = 10
    private val goals = listOf(10, 30, 60, 100)
    private val sources = StarSource.entries

    private inner class Result(
        val n: Int,
        val minutes: Int,
        val perMinute: List<Map<StarSource, Long>>,
        val total: Map<StarSource, Long>,
        val requested: Map<StarSource, Long>,
        val capHits: Map<StarSource, Long>,
        val budget: Double,
        val waves: List<Int>,
        val kill: Map<String, Long>,
        val spotPops: Map<String, List<Long>>,
        val levelMinutes: Map<Int, Double?>,
        val leaderPayout: Int,
        val trailingPayout: Int,
        val budgetPerMinute: List<Double>,
        /** (tick since the run started, stars) of every Mid wave. */
        val waveEvents: List<Pair<Long, Int>>,
        val p3StartTick: Long,
        val warnTicks: List<Long>,
    ) {
        /** Phase [index]'s minutes that the run covers completely or partly. */
        fun phaseMinutes(index: Int): IntRange = phaseStarts[index] until minOf(phaseStarts[index + 1], minutes)
    }

    @Test
    fun `income per player stays on the budget at 3, 6 and 12 players, spots rotate, waves scale, a kill on the leader pays more`() {
        val results = listOf(3, 6, 12).map { run(it, 20) }
        val out = StringBuilder()
        out.appendLine("ADR-0318 star economy, 20 game minutes, shipped content")
        for (r in results) {
            val minutes = r.minutes
            out.appendLine()
            out.appendLine("== N=${r.n}: budget %.1f stars/player over %d min (%.2f /player-min), issued %.1f /player-min ==".format(r.budget / r.n, minutes, r.budget / r.n / minutes, r.total.filterKeys { it != StarSource.BOUNTY }.values.sum().toDouble() / r.n / minutes))
            out.appendLine("source   | per player-min | share of issued | requested | cap hits")
            val issuedAll = r.total.values.sum().toDouble()
            for (s in sources) out.appendLine("%-8s | %6.2f | %5.1f%% | %6d | %4d".format(s.key, r.total.getValue(s).toDouble() / r.n / minutes, 100 * r.total.getValue(s) / issuedAll, r.requested.getValue(s), r.capHits.getValue(s)))
            out.appendLine("total    | %6.2f | 100.0%%".format(issuedAll / r.n / minutes))
            out.appendLine("per minute (stars, whole match): " + r.perMinute.mapIndexed { i, m -> "m$i=${m.values.sum()}" }.joinToString(" "))
            out.appendLine("wave sizes: ${r.waves} (per player at first wave %.1f)".format(r.waves.first().toDouble() / r.n))
            val ground = r.kill.getValue("kill-ground"); val bounty = r.total.getValue(StarSource.BOUNTY)
            out.appendLine("kills: ground burst %d, bounty payouts %d (%d stars); bounty share of all income %.1f%%; (ground stars are re-picked, not new supply)".format(ground, r.kill.getValue("bounty-paid"), bounty, 100.0 * bounty / issuedAll))
            out.appendLine("leader kill payout %d vs trailing kill payout %d".format(r.leaderPayout, r.trailingPayout))
            out.appendLine("minutes to level (carry 10/30/60/100): " + goals.joinToString(" ") { g -> r.levelMinutes[g]?.let { "%.1f".format(it) } ?: "n/a" })
            out.appendLine("timer generator (spot) share: %.1f%% of issued, %.1f%% of the budget".format(100.0 * r.total.getValue(StarSource.SPOT) / issuedAll, 100.0 * r.total.getValue(StarSource.SPOT) / r.budget))
            out.appendLine("spot distribution (pops per candidate spot):")
            r.spotPops.forEach { (gen, pops) -> out.appendLine("  %-26s %s".format(gen, pops.joinToString(" "))) }
            phaseTable(r, out)
        }
        println(out)

        for (r in results) {
            val perPlayerMinute = r.total.filterKeys { it != StarSource.BOUNTY }.values.sum().toDouble() / r.budget
            assertThat(perPlayerMinute).`as`("N=${r.n}: issued supply vs the formula budget").isBetween(0.8, 1.2)
            assertThat(r.total.getValue(StarSource.SPOT).toDouble() / r.budget).`as`("N=${r.n}: timer generators are a minor source (share of the budget)").isLessThanOrEqualTo(0.15)
            assertThat(r.leaderPayout).`as`("N=${r.n}: a kill on the leader pays more than a kill on a trailing player").isGreaterThan(r.trailingPayout)
            assertWaveGate(r)
            assertPhaseBudget(r, 0..1)
            assertP3Budget(r)
            r.spotPops.forEach { (gen, pops) -> assertThat(pops.count { it > 0 }).`as`("$gen uses several spots").isGreaterThanOrEqualTo(3) }
        }
        val firstWavePerPlayer = results.map { it.waves.first().toDouble() / it.n }
        assertThat(firstWavePerPlayer.max() - firstWavePerPlayer.min()).`as`("waves are sized per player: per-player first wave is the same at 3, 6, 12").isLessThanOrEqualTo(1.0)
        assertThat(results.map { it.waves.last() }).`as`("waves grow with N").isSorted()
        val perPlayer = results.map { r -> r.total.filterKeys { it != StarSource.BOUNTY }.values.sum().toDouble() / r.n }
        assertThat(perPlayer.max() / perPlayer.min()).`as`("per-player income at 3, 6 and 12 players is similar").isLessThanOrEqualTo(1.25)
    }

    @Test
    fun `30 minute run at 6 players reaches P5, every phase stays on the budget and the wave runs from P3 on`() {
        val r = run(6, 30)
        val out = StringBuilder("ADR-0318 star economy follow-up, 30 game minutes (P1-P5), N=6, shipped content\n")
        out.appendLine("waves (min:sec since start -> stars): " + r.waveEvents.joinToString(" ") { (t, a) -> "%d:%02d->%d".format(t / 1200, (t / 20) % 60, a) })
        phaseTable(r, out)
        println(out)
        assertWaveGate(r)
        assertThat(r.waveEvents.size).`as`("a wave every 100 s from the P3 start + warn (14:10) to minute 30").isEqualTo(((30 * 60 - 14 * 60 - warnSeconds) / 100) + 1)
        assertPhaseBudget(r, 0..4)
    }

    @Test
    fun `a match without a phase clock keeps the wave ungated`() {
        val r = run(6, 4, phaseClock = false)
        println("no phase clock: waves ${r.waveEvents} warns ${r.warnTicks}")
        assertThat(r.warnTicks.firstOrNull()).`as`("announced 10 s ahead, as before").isBetween(90 * 20L - 4, 90 * 20L + 20)
        assertThat(r.waveEvents.map { it.first }).`as`("the first wave lands at 100 s as before").hasSize(2).first().satisfies({ assertThat(it).isBetween(100 * 20L - 4, 100 * 20L + 20) })
    }

    private fun phaseTable(r: Result, out: StringBuilder) {
        out.appendLine("per phase, stars per player-minute by source (N=${r.n}):")
        out.appendLine("%-14s | %s | total | budget | ratio".format("phase", sources.joinToString(" | ") { "%-6s".format(it.key) }))
        for (i in phaseNames.indices) {
            val range = r.phaseMinutes(i)
            if (range.isEmpty()) continue
            val len = range.count()
            val by = sources.map { s -> range.sumOf { r.perMinute[it].getValue(s) }.toDouble() / r.n / len }
            val issued = range.sumOf { m -> r.perMinute[m].filterKeys { it != StarSource.BOUNTY }.values.sum() }.toDouble()
            val budget = range.sumOf { r.budgetPerMinute[it] }
            out.appendLine("%-14s | %s | %5.2f | %5.2f | %.2f   (%d-%d min)".format(phaseNames[i], by.joinToString(" | ") { "%6.2f".format(it) }, issued / r.n / len, budget / r.n / len, issued / budget, range.first, range.last + 1))
        }
        out.appendLine("first wave at tick %s (P3 start tick %d, warn %d s): %s".format(r.waveEvents.firstOrNull()?.first, r.p3StartTick, warnSeconds, r.waveEvents.firstOrNull()?.let { "+%.1f s after P3 start".format((it.first - r.p3StartTick) / 20.0) }))
        out.appendLine("cap redistribution while the wave is gated (shares %s): open-source caps: %s".format(StarEconomy().shares.entries.joinToString(" ") { "${it.key.key}=${it.value}" }, sources.filter { it != StarSource.WAVE }.joinToString(" ") { "${it.key}=%.4f".format(StarEconomy().effectiveShare(it, 0.0) { s -> if (s == StarSource.WAVE) 1e9 else 0.0 }) }))
    }

    private fun assertWaveGate(r: Result) {
        val first = r.waveEvents.first().first
        assertThat(r.waveEvents.filter { it.first < r.p3StartTick }).`as`("N=${r.n}: no wave before P3").isEmpty()
        assertThat(r.warnTicks.firstOrNull()).`as`("N=${r.n}: the first warning comes right after the P3 start").isBetween(r.p3StartTick, r.p3StartTick + 40L)
        assertThat(first).`as`("N=${r.n}: the first wave lands within warn + 1 s after the P3 start").isBetween(r.p3StartTick + warnSeconds * 20L, r.p3StartTick + (warnSeconds + 1) * 20L)
    }

    /** The per-phase total of the completely covered phases in [phases] stays within 20% of the formula budget. */
    private fun assertPhaseBudget(r: Result, phases: IntRange) {
        for (i in phases) {
            val range = r.phaseMinutes(i)
            if (range.isEmpty()) continue
            val issued = range.sumOf { m -> r.perMinute[m].filterKeys { it != StarSource.BOUNTY }.values.sum() }.toDouble()
            assertThat(issued / range.sumOf { r.budgetPerMinute[it] }).`as`("N=${r.n} ${phaseNames[i]}: issued / budget").isBetween(0.8, 1.2)
        }
    }

    /** P3 (the Mid wave opens): the issued total over the covered P3 minutes stays at most 15% above the budget. */
    private fun assertP3Budget(r: Result) {
        val range = r.phaseMinutes(2)
        assertThat(range.count()).`as`("N=${r.n}: the run covers at least 5 minutes of P3").isGreaterThanOrEqualTo(5)
        val issued = range.sumOf { m -> r.perMinute[m].filterKeys { it != StarSource.BOUNTY }.values.sum() }.toDouble()
        assertThat(issued / range.sumOf { r.budgetPerMinute[it] }).`as`("N=${r.n} ${phaseNames[2]}: issued / budget").isLessThanOrEqualTo(1.15)
    }

    private fun run(n: Int, minutes: Int, phaseClock: Boolean = true): Result {
        var result: Result? = null
        scenario("star-economy-n$n", config) {
            val teams = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))).spawnPoints
                .filter { it.role == com.astarworks.astera.core.domain.stage.SpawnRole.TEAM }.mapNotNull { it.team?.value }.distinct()
            val entries = (0 until n).map { i -> player("${teams[i % teams.size]}-${i / teams.size}", team = teams[i % teams.size], at = Vec3(400.0 + i, groundY, 400.0)) to teams[i % teams.size] }
            val players = entries.map { it.first }
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId: MatchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as com.astarworks.astera.core.domain.Result.Success).value
            entries.forEach { (p, t) -> lifecycle.entry(matchId, p.id, TeamId(t)) }
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(2)
            val p3Until = sim.services.getOrThrow<IMatchPhaseClock>().ticksUntil(matchId, NexusPhase.Phase3Center) ?: error("annihilation has a phase clock")
            if (!phaseClock) sim.services.register<IMatchPhaseClock>(object : IMatchPhaseClock {
                override fun current(matchId: MatchId): MatchPhaseSnapshot? = null
                override fun ticksUntil(matchId: MatchId, target: NexusPhase): Long? = null
            }, priority = 1000)
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val budget = sim.services.getOrThrow<StarBudget>()
            val actions = sim.services.getOrThrow<StarActionService>()
            val gens = sim.services.getOrThrow<IGeneratorRepository>().listForMatch(matchId)
            val spotGens = gens.filter { it.spots.isNotEmpty() && it.cache == null }
            val cacheSpots = gens.first { it.cache != null }.spots
            val shippedEconomy = sim.services.getOrThrow<com.astarworks.astera.match.application.service.StarSupplyClock>().economyOf(matchId)
            players.forEach { it.moveTo(Vec3(400.0, groundY, 400.0)) }
            val t0 = sim.tick
            val p3StartTick = p3Until
            val budgetPerMinute = MutableList(minutes) { 0.0 }
            val warnTicks = mutableListOf<Long>()
            var warnTitles = 0
            val baseTeams = gens.filter { it.id.value.startsWith("annihilation-base-") }.associate { it.id.value.removePrefix("annihilation-base-") to it.position }

            val perMinute = mutableListOf<Map<StarSource, Long>>()
            var lastIssued = sources.associateWith { 0L }
            val lastSpot = mutableMapOf<String, Int>()
            val allSeq = mutableMapOf<String, MutableList<Int>>()
            val spotSeq = mutableMapOf<String, MutableList<Int>>()
            var budgetIntegral = 0.0
            val cumulativeByMinute = mutableListOf<Double>()
            var cumulative = 0.0
            val kills = listOf(4, 7, 10, 13)

            var second = 0
            var lastTick = t0
            while (sim.tick - t0 < minutes * 1200L) {
                second += 1
                val sec = second - 1
                players.forEachIndexed { i, p ->
                    if ((sec + i * 3) % 10 == 0) actions.onOreMined(p.id)                       // 6 ore nodes / min
                    if ((sec + i * 2) % 10 == 0) actions.onBlockBroken(p.id, "COAL_ORE")        // 6 listed blocks / min
                    if ((sec + i * 7) % 20 == 0) p.moveTo(cacheSpots[((sec / 20) * 5 + i * 3) % cacheSpots.size])  // scouting walk to a cache
                }
                // The players pick up whatever lies on the map (the item pickup itself, team-locked drops included).
                // A pickup walk is a short detour: every lying star gets its own walker (a chunk of at most one star per player per
                // tick), and the walkers are back where they stood afterwards, so how many stars lie around (a bigger kill
                // burst) neither leaves stars uncollected nor pulls the scouts off their cache spots.
                val returnTo = mutableMapOf<Int, Vec3>()
                var spent = 0
                if (second % 3 == 0) {
                    // Team-locked base drops need a player of that team; the rest rotate who picks up.
                    val chunks = sim.starDrops.lying().withIndex().groupBy { (k, _) -> k / players.size }
                    for ((_, chunk) in chunks) {
                        val busy = mutableSetOf<Int>()
                        for ((k, d) in chunk) {
                            val owner = baseTeams.entries.firstOrNull { (_, pos) -> pos.distance(d.position) < 14.0 }?.key
                            val pool = players.indices.filter { owner == null || entries[it].second == owner }
                            val start = (k + second) % pool.size
                            val who = pool.indices.map { pool[(start + it) % pool.size] }.firstOrNull { it !in busy } ?: continue
                            busy += who
                            returnTo.putIfAbsent(who, players[who].position)
                            players[who].moveTo(d.position)
                        }
                        advance(1); spent += 1
                    }
                }
                returnTo.forEach { (who, at) -> players[who].moveTo(at) }
                if (kills.any { it * 60 == sec } && n >= 2) {
                    val killer = players[0]
                    val victim = players.drop(1).maxBy { stars.get(matchId, it.id) }
                    sim.eventBus.publish(PlayerKilled(victim.id, killer.id, DamageSource.Environment.Generic, sim.tick))
                }
                advance((20 - spent).coerceAtLeast(0))
                val warnNow = (sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals>() as com.astarworks.astera.core.application.service.presentation.LoggingPresentationVisuals).calls.count { it is com.astarworks.astera.core.application.service.presentation.LoggingPresentationVisuals.Call.Title && it.title.toString().contains("wave-warn") }
                if (warnNow > warnTitles) { warnTitles = warnNow; warnTicks += sim.tick - t0 }
                val delta = (sim.tick - lastTick) / 20.0 * shippedEconomy.budgetPerMinute((sim.tick - t0) / 20.0, n) / 60.0
                budgetIntegral += delta
                budgetPerMinute[((sim.tick - t0 - 1) / 1200).toInt().coerceIn(0, minutes - 1)] += delta
                lastTick = sim.tick
                for (g in spotGens) for (idx in g.spots.indices) {
                    val c = budget.stats.get(matchId, "spot:${g.id.value}:$idx")
                    val seen = spotSeq.getOrPut("${g.id.value}:$idx") { mutableListOf() }
                    if (c > seen.size) { repeat((c - seen.size).toInt()) { seen += 1 }; val prev = lastSpot[g.id.value]; allSeq.getOrPut(g.id.value) { mutableListOf() } += idx; if (prev != null) assertThat(prev).`as`("${g.id.value} pops on the same spot twice in a row at t=${sim.tick - t0}: ${allSeq[g.id.value]}").isNotEqualTo(idx); lastSpot[g.id.value] = idx }
                }
                if (second % 60 == 0) {
                    val issued = sources.associateWith { budget.stats.issued(matchId, it) }
                    perMinute += sources.associateWith { issued.getValue(it) - lastIssued.getValue(it) }
                    lastIssued = issued
                    cumulative = issued.filterKeys { it != StarSource.BOUNTY }.values.sum().toDouble() / n
                    cumulativeByMinute += cumulative
                }
            }
            val total = sources.associateWith { budget.stats.issued(matchId, it) }
            val levelMinutes = goals.associateWith { g -> cumulativeByMinute.indexOfFirst { it >= g }.takeIf { it >= 0 }?.let { it + 1.0 - (cumulativeByMinute[it] - g) / (cumulativeByMinute[it] - (cumulativeByMinute.getOrNull(it - 1) ?: 0.0)) } }
            val waveSupplied = eventsOf<StarSupplied>().filter { it.generatorId.value == "annihilation-center-wave" && it.matchId == matchId }
            val waves = waveSupplied.map { it.amount }
            val waveEvents = waveSupplied.map { (it.atTick - t0) to it.amount }
            val spotPops = spotGens.associate { g -> g.id.value to g.spots.indices.map { budget.stats.get(matchId, "spot:${g.id.value}:$it") } }

            // Leader vs trailing: the same killer takes a leader (40 above the richest carrier) and a trailing player (1 carried). The payout is the bounty only.
            fun payout(carried: Int, victimIdx: Int): Int {
                val victim = players[victimIdx]
                stars.spend(matchId, victim.id, stars.get(matchId, victim.id))
                stars.add(matchId, victim.id, carried)
                val before = stars.get(matchId, players[0].id)
                sim.eventBus.publish(PlayerKilled(victim.id, players[0].id, DamageSource.Environment.Generic, sim.tick))
                return stars.get(matchId, players[0].id) - before
            }
            val trailingPayout = payout(1, n - 1)
            val leaderPayout = payout(players.maxOf { stars.get(matchId, it.id) } + 40, if (n >= 3) n - 2 else 1)
            result = Result(
                n, minutes, perMinute, total,
                sources.associateWith { budget.stats.get(matchId, "requested:${it.key}") },
                sources.associateWith { budget.stats.capHits(matchId, it) },
                budgetIntegral, waves,
                listOf("kill-ground", "bounty-paid").associateWith { budget.stats.get(matchId, it) },
                spotPops, levelMinutes, leaderPayout, trailingPayout,
                budgetPerMinute, waveEvents, p3StartTick, warnTicks,
            )
        }
        return checkNotNull(result)
    }
}
