package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.EconomyPlanner
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Bot lull economy: the very [EconomyPlanner] and [StageObjectives] sites the live BotBrain uses drive a `Bot_` sim player through
 * the shop ladder of the shipped `content/shops/match-shop.yaml` on the real carry-tnt stage. Walking is a teleport to each planned
 * step; ground pickup of base generator drops is not simulated, so a `collect` step hands the generator ore over (the server's own
 * generator code is covered elsewhere). Mining goes through the real OreNodeService, buying through the real MatchShopBotBuyer.
 */
class BotEconomyScenarioTest {

    @Test
    fun `the shipped ladder parses to gold 6, diamond 4, emerald 3 and bag names map to ore ids`() {
        val ladder = EconomyPlanner.parseLadder(Files.readString(GameSim.defaultContentDir().resolve("shops/match-shop.yaml")))
        assertThat(ladder).containsExactly(mapOf("ore-gold" to 6), mapOf("ore-diamond" to 4), mapOf("ore-emerald" to 3))
        assertThat(EconomyPlanner.oreCounts(listOf("Diamond Ore" to 2, "ダイヤ鉱石" to 1, "Stone" to 9, "エメラルド鉱石" to 3)))
            .isEqualTo(mapOf("ore-diamond" to 3, "ore-emerald" to 3))
    }

    @Test
    fun `a bot collects gold at its base, mines diamond and emerald at Mid and buys every weapon tier at its own shop`() {
        scenario("bot-economy-ladder") {
            val objectives = checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), "carry-tnt")) { "stage did not load" }
            val local = checkNotNull(objectives.economySites("red")) { "no economy sites for red" }
            val ladder = objectives.economyLadder()
            assertThat(ladder).hasSize(3)
            assertThat(local.shop).isNotNull
            assertThat(local.generators.map { it.ore }).contains("ore-gold")
            assertThat(local.nodes.map { it.ore }).contains("ore-diamond", "ore-emerald")

            val bot = player("Bot_Eco", team = "red")
            val enemy = player("blue-guard", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(bot to "red", enemy to "blue"), stage = "siege-isles-tnt")
            advance(60)
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles-tnt"))!!.origin
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val bag = checkNotNull(sim.server.view(bot.id)).givenItems
            bag.removeAll { it == "knight-sword" }
            bag += "knight-sword"
            bag += "diamond-pickaxe"

            // Mid diamond / emerald are Norma-level gated (3 / 4): the bot's objective play earns the levels; here the stars and the base check do.
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            repeat(6) {
                stars.add(matchId, bot.id, 40)
                bus.publish(StarsCollected(matchId, bot.id, 1, stars.get(matchId, bot.id)))
                advance(5)
                normaCheckAtHome(matchId, bot)
            }

            val planner = EconomyPlanner(local.shifted(origin.x, origin.z), ladder)
            fun have() = bag.filter { it.startsWith("ore-") }.groupingBy { it }.eachCount()
            val visited = LinkedHashSet<String>()
            var clock = 0L
            val log = ArrayList<String>()
            var guard = 0
            while (!planner.done && guard++ < 3000) {
                val step = planner.step(clock, bot.position.x, bot.position.z, have(), hasPickaxe = true)
                    ?: if (planner.done) break else error("planner stalled at rung ${planner.rung} bag=$bag visited=$visited log=${log.takeLast(12)}")
                visited += "${step.kind}:${step.ore}"
                when (step.kind) {
                    "collect" -> {
                        bot.position = Vec3(step.x, step.y + origin.y, step.z)
                        while (bag.count { it == step.ore } < 6) bag += step.ore // the generator drops reach a standing bot
                    }
                    "shop" -> bot.position = Vec3(step.x + 1.0, step.y + origin.y, step.z)
                    "mine" -> {
                        bot.position = Vec3(step.x, step.y + origin.y, step.z)
                        val cell = checkNotNull(step.strike)
                        val out = nodes.mine(bot.id, worldId, Vec3(cell.first + origin.x, cell.second + origin.y, cell.third + origin.z), ItemId("diamond-pickaxe"))
                        log += "$out"
                    }
                }
                advance(8)
                clock += 400
            }
            assertThat(planner.done).`as`("ladder finished, visited %s", visited).isTrue()
            assertThat(visited).contains("collect:ore-gold", "mine:ore-diamond", "mine:ore-emerald", "shop:")
            assertThat(bag).contains("knight-sword-t4")
            assertThat(bag.count { it == "knight-sword" }).isZero()
        }
    }
}
