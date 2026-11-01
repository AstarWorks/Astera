package com.astarworks.astera.gamesim.playable.metrics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mass-play metrics (ADR-0316, design 4.1): a scripted annihilation match over several seeds on the real shipped
 * content. Every metric column is either a number or an explicit n/a with a reason; metrics.json, PNG frames and
 * summary.md are written.
 */
class MatchMetricsScenarioTest {

    @Test
    fun `scripted annihilation matches fill every metric column and write the summary`() {
        val out = Path.of(System.getProperty("astera.sim.traceDir")).resolveSibling("play-test")
        val seeds = listOf(11L, 12L, 13L)
        val results = MassPlay.run(seeds, out, MassPlay.Config(maxTicks = 12_000, frameEveryTicks = 3_000)) { ScriptedAnnihilationRun(it) }

        assertThat(results).hasSize(3)
        for (r in results) {
            assertThat(out.resolve(r.run).resolve("metrics.json")).exists()
            assertThat(Files.list(out.resolve(r.run)).use { s -> s.filter { it.fileName.toString().endsWith(".png") }.count() }).`as`("frames of ${r.run}").isGreaterThan(0)
            // Every column is a number or has a stated reason.
            for ((key, value) in r.result.scalars) {
                assertThat(value != null || key in r.result.notAvailable).`as`("$key is n/a without a reason in ${r.run}").isTrue()
            }
            assertThat(r.result.scalars["duration_s"]).isGreaterThan(0.0)
            assertThat(r.result.scalars["players"]).isEqualTo(6.0)
        }
        assertThat(results.map { it.result.scalars["pillar_damage_total"] ?: 0.0 }.sum()).`as`("the scripted attackers hurt pillars").isGreaterThan(0.0)
        val summary = Files.readString(out.resolve("summary.md"))
        for (column in listOf("duration_s", "ttff_s", "lead_changes", "comeback", "stars_per_player_minute", "idle_ratio_mean",
            "decisions_per_min_mean", "kills_total", "kills_per_min", "kills_per_min_per_player", "pillar_damage_total", "heatmap_unvisited_ratio", "ore_contention",
            "star_pickup_top_cell_share")) {
            assertThat(summary).contains("| $column |")
        }
        println(summary)
    }
}
