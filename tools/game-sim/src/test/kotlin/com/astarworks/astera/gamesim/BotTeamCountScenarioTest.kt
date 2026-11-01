package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.Mate
import com.astarworks.astera.bots.ModeFacts
import com.astarworks.astera.bots.ModePlanner
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.gamesim.playable.StageTeamLayouts
import com.astarworks.astera.gamesim.playable.metrics.MassPlay
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.hypot

/**
 * Annihilation bots work for any team count (ADR-0323: 4 teams on a 90 degree map, 1v1 on two opposite bases with the other two
 * sealed). The enemy set comes from the teams that actually have players, never from the stage's base list or a fixed team list.
 *
 * Planner level: a synthetic 4-base layout (red N, blue E, green S, yellow W) is played with 2 live teams (red N vs green S, blue
 * and yellow sealed: the pairing is the test's input, nothing is inferred from angles), 3 and 4; the real current 3-base stage
 * covers 3. Match level: the real [MatchDriver] with 2 of the current stage's 3 teams must never touch the unplayed base.
 */
class BotTeamCountScenarioTest {
    private class Facts : ModeFacts {
        val gone = HashSet<Pair<String, Int>>()
        override fun phase() = 3
        override fun pillarStanding(team: String, tier: Int) = (team to tier) !in gone
    }

    private class Layout(val bases: Map<String, Pair<Double, Double>>) : ModePlanner.Zones {
        override fun point(name: String): Triple<Double, Double, Double>? = null
        override fun names(prefix: String): List<String> = emptyList()
        override fun pillars() = bases.flatMap { (team, c) ->
            val len = hypot(c.first, c.second)
            (1..3).map { tier ->
                // Tier 1 outermost, the Nexus (tier 3) nearest the map centre.
                ModePlanner.PillarSite(team, tier, (c.first - c.first / len * 6 * tier).toInt(), 65, (c.second - c.second / len * 6 * tier).toInt(), "pickaxe")
            }
        }
        override fun spawns() = bases.flatMap { (team, c) -> listOf(Triple(team, c.first + 3, c.second), Triple(team, c.first - 3, c.second)) }
    }

    private val four = Layout(mapOf("red" to (0.0 to -100.0), "blue" to (100.0 to 0.0), "green" to (0.0 to 100.0), "yellow" to (-100.0 to 0.0)))

    /**
     * Plays [own] against the [live] teams on [layout]: walks to the chosen pillar, knocks the team out, repeats. Returns the
     * teams burned in order.
     */
    private fun campaign(layout: ModePlanner.Zones, stageTeams: List<String>, live: Set<String>, own: String): List<String> {
        val facts = Facts()
        val planner = ModePlanner(
            "annihilation", "bot", own, stageTeams.filter { it != own }, layout, facts = facts, matchKey = "tc", liveTeams = { live },
        )
        val home = layout.spawns().first { it.first == own }
        planner.noteRoster(listOf(Mate("bot", home.second, home.third)))
        var x = home.second; var z = home.third
        var now = 0L
        val burned = ArrayList<String>()
        repeat(20_000) {
            now += 50
            val step = planner.step(now, x, z) ?: return burned
            if (step.kind == "pillar" && hypot(step.x - x, step.z - z) <= ModePlanner.PILLAR_REACH) {
                val team = step.zone.split('-')[1]
                assertThat(team).`as`("$own digs a live enemy").isIn(live - own)
                burned += team
                (1..3).forEach { facts.gone += team to it }
            } else {
                val d = hypot(step.x - x, step.z - z).coerceAtLeast(1e-6)
                val s = minOf(1.0, d)
                x += (step.x - x) / d * s; z += (step.z - z) / d * s
            }
        }
        error("$own never finished against $live (burned $burned)")
    }

    private fun assertCovers(layout: ModePlanner.Zones, stageTeams: List<String>, live: Set<String>) {
        for (own in live) {
            val burned = campaign(layout, stageTeams, live, own)
            assertThat(burned).`as`("$own burns every live enemy once, none of the sealed bases").containsExactlyInAnyOrderElementsOf(live - own)
        }
    }

    @Test
    fun `1v1 on two opposite bases ignores the sealed bases`() {
        assertCovers(four, four.bases.keys.toList(), setOf("red", "green"))
        assertCovers(four, four.bases.keys.toList(), setOf("blue", "yellow"))
    }

    @Test
    fun `four teams every team burns all three enemies`() {
        assertCovers(four, four.bases.keys.toList(), four.bases.keys)
    }

    @Test
    fun `three teams on a four base layout and on the current stage`() {
        assertCovers(four, four.bases.keys.toList(), setOf("red", "blue", "yellow"))
        val src = checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), "annihilation")) { "annihilation stage did not load" }
        val teams = src.teams()
        assertCovers(checkNotNull(src.zones()), teams, teams.toSet())
    }

    @Test
    fun `a real two team match on the three base stage never touches the unplayed base`() {
        val out = Files.createTempDirectory("bot-team-count")
        // The pairing comes from the stage's team_layouts[2] when the stage has one (c5, ADR-0323); the current 3-base stage has none, so the two teams are named explicitly (MatchConfig.teams).
        val pair = StageTeamLayouts.available(GameSim.defaultContentDir(), "annihilation-arena")[2] ?: listOf("red", "blue")
        var seated: List<String> = emptyList()
        val driver = { seed: Long -> MatchDriver.boot(MatchConfig(seed = seed, teams = pair, botsPerTeam = 3)).also { d -> seated = d.seats.map { it.team }.distinct() } }
        val run = MassPlay.run(listOf(20261102L), out, MassPlay.Config(renderFrames = false), driver).single()
        val s = run.result.scalars
        assertThat(seated).`as`("the scoreboard teams (what the bots read as live) are exactly the configured pairing").containsExactlyElementsOf(pair)
        val unplayed = s.keys.filter { it.startsWith("pillar_damage_taken_") }.map { it.removePrefix("pillar_damage_taken_") }
            .filter { t -> (s["pillar_damage_dealt_$t"] ?: 0.0) == 0.0 && (s["pillar_damage_taken_$t"] ?: 0.0) == 0.0 }
        println("[bot-team-count] end=${run.endReason} untouched=$unplayed dealt=${s.filterKeys { it.startsWith("pillar_damage_dealt_") }}")
        assertThat(unplayed).`as`("exactly the sealed team's base stays untouched").hasSize(1)
        assertThat(s["pillar_damage_total"] ?: 0.0).`as`("the two live teams fight over pillars").isGreaterThan(0.0)
    }
}
