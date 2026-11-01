package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.service.StarActionService
import com.astarworks.astera.match.application.service.StarBudget
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.star.StarSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The star economy of the annihilation-arena (ADR-0318) on the real shipped content: a budget of
 * `per_player_minute(t) x N_alive`, drawn by timer generators (candidate spots, rotation), mining, block breaks, exploration
 * caches, Mid waves sized by N, and a kill/bounty rule. Played for 15 game minutes with 3, 6 and 12 players who mine,
 * break blocks, walk to caches, pick up what lies on the map and kill now and then. Prints the table that goes into the ADR
 * and asserts: per-player income stays within 20% of the formula budget at every N, timer generators stay a minor source
 * and never pop on the same spot twice in a row, waves grow with N, and a kill on the leader pays more than one on a trailing player.
 */
class StarEconomyScenarioTest {

    private val config = AnnihilationConfig(buildPhaseTicks = 1200, engagePhaseTicks = 12000, tier3PhaseTicks = 6000, suddenDeathTicks = 1200)
    private val minutes = 15
    private val goals = listOf(10, 30, 60, 100)
    private val sources = StarSource.entries

    private class Result(
        val n: Int,
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
    )

    @Test
    fun `income per player stays on the budget at 3, 6 and 12 players, spots rotate, waves scale, a kill on the leader pays more`() {
        val results = listOf(3, 6, 12).map { run(it) }
        val out = StringBuilder()
        out.appendLine("ADR-0318 star economy, 15 game minutes, shipped content")
        for (r in results) {
            out.appendLine()
            out.appendLine("== N=${r.n}: budget %.1f stars/player over %d min (%.2f /player-min), issued %.1f /player-min ==".format(r.budget / r.n, minutes, r.budget / r.n / minutes, r.total.filterKeys { it != StarSource.BOUNTY }.values.sum().toDouble() / r.n / minutes))
            out.appendLine("source   | per player-min | share of issued | requested | cap hits")
            val issuedAll = r.total.values.sum().toDouble()
            for (s in sources) out.appendLine("%-8s | %6.2f | %5.1f%% | %6d | %4d".format(s.key, r.total.getValue(s).toDouble() / r.n / minutes, 100 * r.total.getValue(s) / issuedAll, r.requested.getValue(s), r.capHits.getValue(s)))
            out.appendLine("total    | %6.2f | 100.0%%".format(issuedAll / r.n / minutes))
            out.appendLine("per minute (stars, whole match): " + r.perMinute.mapIndexed { i, m -> "m$i=${m.values.sum()}" }.joinToString(" "))
            out.appendLine("wave sizes: ${r.waves} (per player at first wave %.1f)".format(r.waves.first().toDouble() / r.n))
            val direct = r.kill.getValue("kill-direct"); val bounty = r.total.getValue(StarSource.BOUNTY)
            out.appendLine("kills: direct %d, ground %d, lost %d, bounty payouts %d (%d stars); kill income share of all income %.1f%%".format(direct, r.kill.getValue("kill-ground"), r.kill.getValue("kill-lost"), r.kill.getValue("bounty-paid"), bounty, 100.0 * (direct + bounty) / (issuedAll + direct)))
            out.appendLine("leader kill payout %d vs trailing kill payout %d".format(r.leaderPayout, r.trailingPayout))
            out.appendLine("minutes to level (carry 10/30/60/100): " + goals.joinToString(" ") { g -> r.levelMinutes[g]?.let { "%.1f".format(it) } ?: "n/a" })
            out.appendLine("timer generator (spot) share: %.1f%% of issued, %.1f%% of the budget".format(100.0 * r.total.getValue(StarSource.SPOT) / issuedAll, 100.0 * r.total.getValue(StarSource.SPOT) / r.budget))
            out.appendLine("spot distribution (pops per candidate spot):")
            r.spotPops.forEach { (gen, pops) -> out.appendLine("  %-26s %s".format(gen, pops.joinToString(" "))) }
        }
        println(out)

        for (r in results) {
            val perPlayerMinute = r.total.filterKeys { it != StarSource.BOUNTY }.values.sum().toDouble() / r.budget
            assertThat(perPlayerMinute).`as`("N=${r.n}: issued supply vs the formula budget").isBetween(0.8, 1.2)
            assertThat(r.total.getValue(StarSource.SPOT).toDouble() / r.budget).`as`("N=${r.n}: timer generators are a minor source (share of the budget)").isLessThanOrEqualTo(0.15)
            assertThat(r.leaderPayout).`as`("N=${r.n}: a kill on the leader pays more than a kill on a trailing player").isGreaterThan(r.trailingPayout)
            assertThat(r.waves.size).`as`("N=${r.n}: one wave per 100 s").isEqualTo(9)
            r.spotPops.forEach { (gen, pops) -> assertThat(pops.count { it > 0 }).`as`("$gen uses several spots").isGreaterThanOrEqualTo(3) }
        }
        val firstWavePerPlayer = results.map { it.waves.first().toDouble() / it.n }
        assertThat(firstWavePerPlayer.max() - firstWavePerPlayer.min()).`as`("waves are sized per player: per-player first wave is the same at 3, 6, 12").isLessThanOrEqualTo(1.0)
        assertThat(results.map { it.waves.last() }).`as`("waves grow with N").isSorted()
        val perPlayer = results.map { r -> r.total.filterKeys { it != StarSource.BOUNTY }.values.sum().toDouble() / r.n }
        assertThat(perPlayer.max() / perPlayer.min()).`as`("per-player income at 3, 6 and 12 players is similar").isLessThanOrEqualTo(1.25)
    }

    private fun run(n: Int): Result {
        var result: Result? = null
        scenario("star-economy-n$n", config) {
            val teams = listOf("red", "blue", "yellow")
            val entries = (0 until n).map { i -> player("${teams[i % 3]}-${i / 3}", team = teams[i % 3], at = Vec3(400.0 + i, groundY, 400.0)) to teams[i % 3] }
            val players = entries.map { it.first }
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId: MatchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as com.astarworks.astera.core.domain.Result.Success).value
            entries.forEach { (p, t) -> lifecycle.entry(matchId, p.id, TeamId(t)) }
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(2)
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val budget = sim.services.getOrThrow<StarBudget>()
            val actions = sim.services.getOrThrow<StarActionService>()
            val gens = sim.services.getOrThrow<IGeneratorRepository>().listForMatch(matchId)
            val spotGens = gens.filter { it.spots.isNotEmpty() && it.cache == null }
            val cacheSpots = gens.first { it.cache != null }.spots
            val shippedEconomy = sim.services.getOrThrow<com.astarworks.astera.match.application.service.StarSupplyClock>().economyOf(matchId)
            players.forEach { it.moveTo(Vec3(400.0, groundY, 400.0)) }
            val t0 = sim.tick
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
                    if ((sec + i * 3) % 15 == 0) actions.onOreMined(p.id)                       // 4 ore nodes / min
                    if ((sec + i * 2) % 10 == 0) actions.onBlockBroken(p.id, "COAL_ORE")        // 6 listed blocks / min
                    if ((sec + i * 7) % 20 == 0) p.moveTo(cacheSpots[((sec / 20) * 5 + i * 3) % cacheSpots.size])  // scouting walk to a cache
                }
                // The players pick up whatever lies on the map (the item pickup itself, team-locked drops included).
                if (second % 3 == 0) {
                    // One player walks over each lying star (team-locked base drops: a player of that team), rotating who picks up.
                    sim.starDrops.lying().forEachIndexed { k, d ->
                        val owner = baseTeams.entries.firstOrNull { (_, pos) -> pos.distance(d.position) < 14.0 }?.key
                        val pool = players.indices.filter { owner == null || entries[it].second == owner }
                        players[pool[(k + second) % pool.size]].moveTo(d.position)
                    }
                }
                if (kills.any { it * 60 == sec } && n >= 2) {
                    val killer = players[0]
                    val victim = players.drop(1).maxBy { stars.get(matchId, it.id) }
                    sim.eventBus.publish(PlayerKilled(victim.id, killer.id, DamageSource.Environment.Generic, sim.tick))
                }
                advance(20)
                budgetIntegral += (sim.tick - lastTick) / 20.0 * shippedEconomy.budgetPerMinute((sim.tick - t0) / 20.0, n) / 60.0
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
            val waves = eventsOf<StarSupplied>().filter { it.generatorId.value == "annihilation-center-wave" && it.matchId == matchId }.map { it.amount }
            val spotPops = spotGens.associate { g -> g.id.value to g.spots.indices.map { budget.stats.get(matchId, "spot:${g.id.value}:$it") } }

            // Leader vs trailing: the same killer takes a leader (100 carried) and a trailing player (5 carried).
            fun payout(carried: Int, victimIdx: Int): Int {
                val victim = players[victimIdx]
                stars.spend(matchId, victim.id, stars.get(matchId, victim.id))
                stars.add(matchId, victim.id, carried)
                val before = stars.get(matchId, players[0].id)
                sim.eventBus.publish(PlayerKilled(victim.id, players[0].id, DamageSource.Environment.Generic, sim.tick))
                return stars.get(matchId, players[0].id) - before
            }
            val trailingPayout = payout(5, n - 1)
            val leaderPayout = payout(100, if (n >= 3) n - 2 else 1)
            result = Result(
                n, perMinute, total,
                sources.associateWith { budget.stats.get(matchId, "requested:${it.key}") },
                sources.associateWith { budget.stats.capHits(matchId, it) },
                budgetIntegral, waves,
                listOf("kill-direct", "kill-ground", "kill-lost", "bounty-paid").associateWith { budget.stats.get(matchId, it) },
                spotPops, levelMinutes, leaderPayout, trailingPayout,
            )
        }
        return checkNotNull(result)
    }
}
