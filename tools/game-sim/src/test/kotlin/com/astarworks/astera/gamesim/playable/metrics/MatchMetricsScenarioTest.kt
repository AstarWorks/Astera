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
            assertThat(r.result.scalars["p1_kills_in_enemy_base"]).`as`("p1_kills_in_enemy_base of ${r.run} is a count, with a per-kill list in metrics.json").isNotNull().isGreaterThanOrEqualTo(0.0)
            assertThat(Files.readString(out.resolve(r.run).resolve("metrics.json"))).contains("\"p1KillsInEnemyBaseList\"")
            assertThat(Files.readString(out.resolve(r.run).resolve("metrics.json"))).contains("\"p2TtffSeconds\"", "\"p2KillsPerMinute\"")
            assertThat(r.result.scalars).containsKeys("p2_ttff_s", "p2_kills_per_min")
            r.result.scalars["p2_ttff_s"]?.let { assertThat(it).`as`("p2_ttff_s of ${r.run}").isGreaterThanOrEqualTo(0.0) }
            r.result.scalars["p2_kills_per_min"]?.let { assertThat(it).`as`("p2_kills_per_min of ${r.run}").isGreaterThanOrEqualTo(0.0) }
        }
        assertThat(results.map { it.result.scalars["pillar_damage_total"] ?: 0.0 }.sum()).`as`("the scripted attackers hurt pillars").isGreaterThan(0.0)
        val summary = Files.readString(out.resolve("summary.md"))
        for (column in listOf("duration_s", "ttff_s", "lead_changes", "comeback", "stars_per_player_minute", "idle_ratio_mean",
            "decisions_per_min_mean", "kills_total", "p2_ttff_s", "p2_kills_per_min", "kills_per_min", "kills_per_min_per_player", "pillar_damage_total", "heatmap_unvisited_ratio", "ore_contention",
            "star_pickup_top_cell_share")) {
            assertThat(summary).contains("| $column |")
        }
        println(summary)
    }

    @Test
    fun `real bot matches driven by MatchDriver are measured through the same pipeline`() {
        val out = Path.of(System.getProperty("astera.sim.traceDir")).resolveSibling("play-test-driver")
        val results = MassPlay.run(listOf(21L, 22L), out, MassPlay.Config(maxTicks = 3_000, frameEveryTicks = 0)) { MatchDriver.boot(MatchConfig(seed = it)) }

        assertThat(results).hasSize(2)
        for (r in results) {
            assertThat(out.resolve(r.run).resolve("metrics.json")).exists()
            assertThat(r.result.scalars["players"]).isEqualTo(9.0)
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
            val offer = catalog.offers.first { it.upgradeFrom == null && it.price.size == 1 && it.stack == 1 }
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
            repeat(count) { bag += ore.value }
            assertThat(purchase.execute(buyer.id, offer)).isInstanceOf(Result.Success::class.java)

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
