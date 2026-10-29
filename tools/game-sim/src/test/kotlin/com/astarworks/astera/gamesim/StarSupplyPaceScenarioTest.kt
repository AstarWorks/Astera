package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Star pace of the annihilation-arena (founder 2026-10-29, anti-snowball supply), measured on the real shipped content
 * over the real phase timeline (build 60 s, engage 600 s, tier 3 300 s). Counts every [StarSupplied] (the supply after the
 * formula in content/norma-layer/layer.yaml), prints stars per minute and per phase and the time the best collector of a
 * 3-team lane (4 players per team) needs for each Norma star goal, and asserts the shape: supply keeps growing, Mid waves
 * are announced ahead and land on schedule, the base generators only feed their own team, and level 5 (100 stars) is
 * reachable at about 7-8 minutes for an active team player. The table goes into docs/game-design/stars-and-norma.md "Pace".
 */
class StarSupplyPaceScenarioTest {

    private val config = AnnihilationConfig(
        buildPhaseTicks = 1200, engagePhaseTicks = 12000, tier3PhaseTicks = 6000, suddenDeathTicks = 1200,
    )

    /** Share of the neutral supply, and of the own base supply, the best collector of a 4-player team nets. */
    private val bestNeutralShare = 0.18
    private val bestBaseShare = 0.40
    private val goals = listOf(10, 30, 60, 100)

    @Test
    fun `the star supply grows over the match, waves are announced and land, bases feed only their team`() {
        scenario("star-supply-pace", config) {
            val red = player("red-1", team = "red", at = Vec3(400.0, groundY, 400.0))
            val blue = player("blue-1", team = "blue", at = Vec3(400.0, groundY, 410.0))
            val yellow = player("yellow-1", team = "yellow", at = Vec3(400.0, groundY, 420.0))
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, red.id, TeamId("red"))
            lifecycle.entry(matchId, blue.id, TeamId("blue"))
            lifecycle.entry(matchId, yellow.id, TeamId("yellow"))
            lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
            val t0 = sim.tick
            assertThat(eventsOf<MatchStarted>().map { it.matchId }).contains(matchId)

            // (a) A base generator feeds only its own team: blue stands on red's base generator through a pop, takes nothing.
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val redBase = sim.services.getOrThrow<IGeneratorRepository>().listForMatch(matchId).first { it.id.value == "annihilation-base-red" }
            blue.moveTo(redBase.position)
            advance(500)
            assertThat(eventsOf<StarSupplied>().any { it.generatorId == redBase.id && it.team == TeamId("red") }).`as`("red's base popped").isTrue()
            assertThat(sim.starDrops.lying().any { it.position.distance(redBase.position) < 2.0 }).`as`("the pop lies at red's base").isTrue()
            assertThat(stars.get(matchId, blue.id)).`as`("blue cannot take red's base stars").isEqualTo(0)
            blue.moveTo(Vec3(400.0, groundY, 410.0))
            red.moveTo(redBase.position)
            advance(40)
            assertThat(stars.get(matchId, red.id)).`as`("red takes its own base stars").isGreaterThan(0)
            red.moveTo(Vec3(400.0, groundY, 400.0))

            // (b) Run the timeline second by second; note when the wave warning (title) first reaches red.
            val warnKey = "astera.match.star.wave-warn.title"
            val warnSeconds = mutableListOf<Int>()
            var seen = 0
            val endSeconds = 990
            var second = (sim.tick - t0).toInt() / 20
            while (second < endSeconds) {
                advance(20)
                second += 1
                val titles = checkNotNull(sim.server.view(red.id)).titles.count { it.value == warnKey }
                if (titles > seen) { warnSeconds += second; seen = titles }
            }

            // (c) Measure.
            val supplied = eventsOf<StarSupplied>().filter { it.matchId == matchId }.map { ((it.atTick - t0) / 20.0) to it }
            fun minuteSum(from: Int, to: Int, pick: (StarSupplied) -> Boolean) =
                supplied.filter { it.first >= from * 60 && it.first < to * 60 && pick(it.second) }.sumOf { it.second.amount }
            val neutral = { e: StarSupplied -> e.team == null }
            val baseOne = { e: StarSupplied -> e.team == TeamId("red") }
            val sb = StringBuilder()
            sb.appendLine("min | neutral/min | one team base/min | stage total/min (neutral + 3 bases)")
            val perMinute = (0 until 16).map { m -> Triple(minuteSum(m, m + 1, neutral), minuteSum(m, m + 1, baseOne), minuteSum(m, m + 1) { true }) }
            perMinute.forEachIndexed { m, (n, b, all) -> sb.appendLine("%2d-%2d | %5d | %5d | %5d".format(m, m + 1, n, b, all)) }
            val phaseRate = mutableMapOf<String, Double>()
            fun phase(name: String, fromSec: Int, toSec: Int) {
                val minutes = (toSec - fromSec) / 60.0
                val n = supplied.filter { it.first >= fromSec && it.first < toSec && neutral(it.second) }.sumOf { it.second.amount }
                val b = supplied.filter { it.first >= fromSec && it.first < toSec && baseOne(it.second) }.sumOf { it.second.amount }
                phaseRate[name] = (n + 3 * b) / minutes
                sb.appendLine("phase %-8s %3d-%3d s: neutral %.1f/min, one team base %.1f/min, stage total %.1f/min".format(name, fromSec, toSec, n / minutes, b / minutes, (n + 3 * b) / minutes))
            }
            phase("build", 0, 60); phase("engage", 60, 660); phase("tier3", 660, 960)

            // Best collector of a 4-player team in a 3-team lane: a fixed share of the neutral supply plus of its own base.
            var cumulative = 0.0
            val reached = mutableMapOf<Int, Double>()
            for ((sec, e) in supplied.sortedBy { it.first }) {
                cumulative += when { neutral(e) -> e.amount * bestNeutralShare; baseOne(e) -> e.amount * bestBaseShare; else -> 0.0 }
                for (g in goals) if (g !in reached && cumulative >= g) reached[g] = sec / 60.0
            }
            sb.appendLine("best collector (neutral share $bestNeutralShare, own base share $bestBaseShare), minutes to carry:")
            goals.forEach { sb.appendLine("  %3d stars: %s".format(it, reached[it]?.let { m -> "%.1f min".format(m) } ?: "not reached")) }
            println(sb)

            // (d) Asserts.
            val waves = supplied.filter { it.second.generatorId.value == "annihilation-center-wave" }
            assertThat(waves.size).`as`("one wave per 100 s up to 990 s").isEqualTo(9)
            waves.forEachIndexed { i, (sec, _) -> assertThat(sec).`as`("wave ${i + 1} lands on schedule").isBetween((i + 1) * 100.0, (i + 1) * 100.0 + 2.5) }
            assertThat(waves.map { it.second.amount }).`as`("waves only grow").isSorted()
            assertThat(waves.last().second.amount).`as`("wave size reaches the clamp (30 x 2.5)").isEqualTo(75)
            assertThat(warnSeconds.size).`as`("every wave is announced").isEqualTo(9)
            warnSeconds.forEachIndexed { i, s -> assertThat(s).`as`("wave ${i + 1} warned 10 s ahead").isBetween((i + 1) * 100 - 11, (i + 1) * 100 - 8) }
            // Waves land every 100 s, so single minutes are lumpy; the phases are the honest comparison.
            assertThat(phaseRate["tier3"]!!).`as`("tier 3 supplies more per minute than engage").isGreaterThan(phaseRate["engage"]!! * 1.2)
            assertThat(phaseRate["engage"]!!).`as`("engage supplies more per minute than build").isGreaterThan(phaseRate["build"]!! * 1.5)
            assertThat(reached[10]!!).isLessThan(reached[30]!!)
            assertThat(reached[100]).`as`("level 5 reachable").isNotNull()
            assertThat(reached[100]!!).`as`("level 5 for an active team player lands around 7-8 minutes").isBetween(6.5, 8.5)
        }
    }
}
