package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.economy.application.service.MatchShopLoaderService
import com.astarworks.astera.economy.application.usecase.shop.MatchShopPurchaseUseCase
import com.astarworks.astera.gamesim.GameSim
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mass-play metrics (ADR-0316, design 4.1): a scripted annihilation match over several seeds on the real shipped
 * content. Every metric column is either a number or an explicit n/a with a reason; metrics.json, PNG frames and
 * summary-<first>-<last>.md are written.
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
            assertThat(r.result.scalars["p1_kills_in_enemy_base"]).`as`("p1_kills_in_enemy_base of ${r.run} is a count, with a per-kill list in metrics.json").isNotNull().isGreaterThanOrEqualTo(0.0)
            assertThat(Files.readString(out.resolve(r.run).resolve("metrics.json"))).contains("\"p1KillsInEnemyBaseList\"")
            assertThat(Files.readString(out.resolve(r.run).resolve("metrics.json"))).contains("\"p2TtffSeconds\"", "\"killsByPhase\"", "\"minutesByPhase\"", "\"stallWindowsP3Plus\"")
            assertThat(r.result.scalars).containsKeys("p2_ttff_s", "kills_p1", "kills_p2", "kills_p3", "kills_p4plus", "minutes_p1", "minutes_p2", "minutes_p3", "minutes_p4plus", "stall_windows_p3plus")
            // Definition: the phases partition the match, so phase kills and phase minutes add up to the match totals.
            val phases = listOf("p1", "p2", "p3", "p4plus")
            assertThat(phases.sumOf { r.result.scalars["kills_$it"]!! }).`as`("phase kills of ${r.run}").isEqualTo(r.result.scalars["kills_total"])
            assertThat(phases.sumOf { r.result.scalars["minutes_$it"]!! }).`as`("phase minutes of ${r.run}").isCloseTo(r.result.scalars["duration_s"]!! / 60.0, org.assertj.core.data.Offset.offset(1e-9))
            assertThat(r.result.scalars["stall_windows_p3plus"]).`as`("stall windows of ${r.run}").isGreaterThanOrEqualTo(0.0)
            r.result.scalars["p2_ttff_s"]?.let { assertThat(it).`as`("p2_ttff_s of ${r.run}").isGreaterThanOrEqualTo(0.0) }
        }
        assertThat(results.map { it.result.scalars["pillar_damage_total"] ?: 0.0 }.sum()).`as`("the scripted attackers hurt pillars").isGreaterThan(0.0)
        val summary = Files.readString(out.resolve(MassPlay.summaryFileName(seeds)))
        for (column in listOf("duration_s", "ttff_s", "lead_changes", "comeback", "stars_per_player_minute", "idle_ratio_mean",
            "decisions_per_min_mean", "kills_total", "p2_ttff_s", "kills_p2", "minutes_p2", "stall_windows_p3plus", "kills_per_min", "kills_per_min_per_player", "pillar_damage_total", "heatmap_unvisited_ratio", "ore_contention",
            "star_pickup_top_cell_share")) {
            assertThat(summary).contains("| $column |")
        }
        // Headline = pooled kills/min = sum(kills) / sum(minutes), recomputed here from the per-match scalars.
        val pooled = results.sumOf { it.result.scalars["kills_total"]!! } / results.sumOf { it.result.scalars["duration_s"]!! / 60.0 }
        assertThat(summary).contains("**Headline: pooled kills/min = ${"%.2f".format(pooled)}**", "bootstrap 90% interval", "| P2+ (P2 + P3 + P4+) |", "| P4+ escalation / sudden death |")
        assertThat(summary.lineSequence().first { it.isNotBlank() && !it.startsWith("#") }).`as`("stall line comes first").startsWith("Stall signature: matches with at least one stall window")
        println(summary)
    }

    @Test
    fun `pooled and per-phase kill rates weigh minutes not matches, the interval is deterministic, stalls are counted`() {
        fun run(seed: Long, endReason: String, kills: Double, minutes: Double, phase: List<Pair<Double, Double>>, stalls: Double): MassPlay.RunResult {
            val scalars = linkedMapOf<String, Double?>("kills_total" to kills, "duration_s" to minutes * 60, "kills_per_min" to (if (minutes > 0) kills / minutes else null), "stall_windows_p3plus" to stalls)
            listOf("p1", "p2", "p3", "p4plus").forEachIndexed { i, ph -> scalars["kills_$ph"] = phase[i].first; scalars["minutes_$ph"] = phase[i].second }
            val json = kotlinx.serialization.json.JsonObject(mapOf("end_reason" to kotlinx.serialization.json.JsonPrimitive(endReason)))
            return MassPlay.RunResult("seed-$seed", seed, MatchMetricsResult(json, scalars, emptyMap()), endReason)
        }
        // A 30-minute stall (1 kill/min) and a 10-minute elimination (4 kills/min): median-of-matches would say 2.5, pooled says 70/40 = 1.75.
        val stall = run(1, "hp_decision", 30.0, 30.0, listOf(0.0 to 6.0, 6.0 to 8.0, 10.0 to 8.0, 14.0 to 8.0), 2.0)
        val elim = run(2, "elimination", 40.0, 10.0, listOf(0.0 to 6.0, 40.0 to 4.0, 0.0 to 0.0, 0.0 to 0.0), 0.0)
        val timeout = run(3, "running", 0.0, 0.0, listOf(0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0), 1.0)
        val results = listOf(stall, elim, timeout)
        val summary = MassPlay.summary(results)

        assertThat(MassPlay.pooledRate(70.0, 40.0)).isEqualTo(1.75)
        assertThat(summary).contains("**Headline: pooled kills/min = 1.75** (70 kills / 40.0 match-min")
        assertThat(summary).contains("Per-match median 2.50, mean 2.50")
        assertThat(summary).contains("| P1 build | 0 | 12.0 | 0.00 |", "| P2 engage | 46 | 12.0 | 3.83 |", "| P3 center | 10 | 8.0 | 1.25 |", "| P4+ escalation / sudden death | 14 | 8.0 | 1.75 |", "| P2+ (P2 + P3 + P4+) | 70 | 28.0 | 2.50 |")
        assertThat(summary).contains("Stall signature: matches with at least one stall window 2 of 3; total stall windows 3")
        assertThat(summary).contains("Stall share = (hp_decision + timeout) / matches = (1 + 1) / 3 = 66.7%")
        assertThat(summary).contains("Match ends: elimination 1, hp_decision (stall) 1, draw 0, timeout (running at cap) 1, other 0.")

        val kills = listOf(30.0, 40.0); val minutes = listOf(30.0, 10.0)
        val ci = MassPlay.bootstrapInterval(kills, minutes)!!
        assertThat(MassPlay.bootstrapInterval(kills, minutes)).`as`("fixed RNG seed: same interval every call").isEqualTo(ci)
        // Two matches: every resample is one of (stall,stall)=1.0, (stall,elim)=1.75, (elim,elim)=4.0, so the interval is bounded by those.
        assertThat(ci.first).isBetween(1.0, 4.0)
        assertThat(ci.second).isBetween(ci.first, 4.0)
    }

    @Test
    fun `real bot matches driven by MatchDriver are measured through the same pipeline`() {
        val out = Path.of(System.getProperty("astera.sim.traceDir")).resolveSibling("play-test-driver")
        val stageTeams = com.astarworks.astera.gamesim.playable.StageTeams.of("annihilation-arena")
        val results = MassPlay.run(listOf(21L, 22L), out, MassPlay.Config(maxTicks = 3_000, frameEveryTicks = 0)) { MatchDriver.boot(MatchConfig(seed = it)) }

        assertThat(results).hasSize(2)
        assertThat(results.map { it.seed }.distinct()).`as`("a batch must not replay one seed").hasSize(2)
        assertThatThrownBy { MassPlay.run(listOf(5L, 5L), out) { MatchDriver.boot(MatchConfig(seed = it)) } }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("duplicate seeds 5")
        assertThat(out.resolve("summary-21-22.md")).content().contains("| seed |")
        for (r in results) {
            assertThat(out.resolve(r.run).resolve("metrics.json")).content().contains("\"seed\": ${r.seed}")
            assertThat(out.resolve(r.run).resolve("metrics.json")).exists()
            assertThat(r.result.scalars["players"]).isEqualTo((stageTeams.size * 3).toDouble())
            assertThat((r.result.json["teams"] as kotlinx.serialization.json.JsonObject).keys).containsExactlyInAnyOrderElementsOf(stageTeams)
            assertThat(r.result.scalars["duration_s"]).isGreaterThan(0.0)
            for ((key, value) in r.result.scalars) {
                assertThat(value != null || key in r.result.notAvailable).`as`("$key is n/a without a reason in ${r.run}").isTrue()
            }
            assertThat(r.result.scalars["ore_contention"]).`as`("ore_contention of ${r.run}: ${r.result.notAvailable["ore_contention"]}").isNotNull()
            assertThat(r.result.scalars["ore_per_role"]).`as`("ore_per_role of ${r.run}: ${r.result.notAvailable["ore_per_role"]}").isNotNull().isGreaterThan(0.0)
        }
    }

    @Test
    fun `first match-shop purchase is recorded per player with tick item and cost, a non-buyer is null`() {
        val run = ScriptedAnnihilationRun(31L)
        try {
            val sim = run.sim
            val metrics = MatchMetrics(sim)
            run.onTick { metrics.onTick(it) }
            val catalog = checkNotNull(MatchShopLoaderService().parse(GameSim.defaultContentDir().resolve("shops")).second) { "match-shop.yaml did not load" }
            // A base weapon is refused once owned (the starter kit carries some) and the test buys twice: take a repeatable offer.
            val offer = catalog.offers.first { !it.isBaseWeapon && it.upgradeFrom == null && it.price.size == 1 && it.stack == 1 }
            val purchase = MatchShopPurchaseUseCase(
                itemRegistry = sim.services.getOrThrow<IItemRegistry>(),
                server = sim.server,
                stock = { sim.services.getOrThrow<IOwnedItemStock>() },
                delivery = { sim.services.getOrThrow<IItemDelivery>() },
                inMatch = { true },
            )
            val buyer = sim.world.entities.first { it.name == "red-2" }
            val bag = checkNotNull(sim.server.view(buyer.id)).givenItems
            val (ore, count) = offer.price.entries.single()

            run.step(100)
            // Short on ore: refused, and a refused buy is not a first purchase.
            assertThat(purchase.execute(buyer.id, offer)).isInstanceOf(Result.Failure::class.java)
            repeat(count + 3) { bag += ore.value }
            val boughtAt = sim.tick
            assertThat(purchase.execute(buyer.id, offer)).isInstanceOf(Result.Success::class.java)
            run.step(60)
            // A second buy later must not replace the first.
            // Starter kit items are already owned; the first other single-ore offer the buyer can still take is the later buy.
            val later = catalog.offers.filter { it.upgradeFrom == null && it.price.size == 1 && it.stack == 1 && it.itemId != offer.itemId }.firstOrNull { o ->
                val (ore2, count2) = o.price.entries.single()
                repeat(count2) { bag += ore2.value }
                (purchase.execute(buyer.id, o) is Result.Success).also { ok -> if (!ok) repeat(count2) { bag.remove(ore2.value) } }
            }
            assertThat(later).`as`("a second purchasable offer").isNotNull()

            val json = metrics.finish().json
            val players = json["players"]!!.jsonObject
            val first = players["red-2"]!!.jsonObject["first_purchase"]!!.jsonObject
            assertThat(first["tick"]!!.jsonPrimitive.content.toLong()).isEqualTo(boughtAt)
            assertThat(first["item"]!!.jsonPrimitive.content).isEqualTo(offer.itemId.value)
            assertThat(first["cost"]!!.jsonObject[ore.value]!!.jsonPrimitive.content.toInt()).isEqualTo(count)
            assertThat(first["cost"]!!.jsonObject).hasSize(1)
            val start = json["startTick"]!!.jsonPrimitive.content.toLong()
            assertThat(first["seconds"]!!.jsonPrimitive.content.toDouble()).isEqualTo((boughtAt - start) / 20.0)
            assertThat(players["red-1"]!!.jsonObject["first_purchase"]).isEqualTo(JsonNull)
            assertThat(json["neverBought"]!!.jsonPrimitive.content.toInt()).isEqualTo(5)
            assertThat(json["firstBuyMedianSeconds"]!!.jsonPrimitive.content.toDouble()).isEqualTo((boughtAt - start) / 20.0)
        } finally {
            run.sim.shutdown()
        }
    }
}
