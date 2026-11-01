package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.match.application.service.StarBudget
import com.astarworks.astera.match.application.service.StarSupplyClock
import com.astarworks.astera.match.domain.event.StarSupplied
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.star.StarSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Action stars in the sim (astera-5c): a mine or a block break in game-sim pays through the same `StarActionService` calls as the Paper
 * listeners (`PaperOreNodeListener`, `PaperStarBlockListener`), so the sim's star economy is not understated.
 */
class SimActionStarsScenarioTest {

    private val config = AnnihilationConfig(pillarHp = 1000, buildPhaseTicks = 4000, engagePhaseTicks = 200, centerPhaseTicks = 200, escalationPhaseTicks = 200, suddenDeathTicks = 400)

    @Test
    fun `mining a node and breaking a listed block each pay stars, an unlisted block pays nothing`() {
        scenario("sim-action-stars", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val paid = mutableListOf<StarsCollected>()
            sim.services.getOrThrow<IDomainEventBus>().subscribe(StarsCollected::class) { if (it.playerId == red.id) paid += it }
            red.holds("diamond-pickaxe")
            advance(25)

            // Mine: swing at the phase-1 iron node until it breaks (hit cooldown), through the sim's break path.
            val iron = Vec3(origin.x + 19, 61.0, origin.z - 28)
            var swings = 0
            while (paid.isEmpty() && swings++ < 40) { advance(7); sim.breakBlock(red, iron) }
            assertThat(paid).`as`("the mined node paid mining stars").hasSize(1)
            assertThat(paid.single().amount).isEqualTo(1) // star_mining_per_node in content/norma-layer/layer.yaml

            // Break: a listed material pays star_block_per_break; an unlisted one does not.
            val logCell = Vec3(origin.x + 3, 80.0, origin.z + 3)
            val dirtCell = Vec3(origin.x + 4, 80.0, origin.z + 3)
            sim.world.placeBlock(WorldId("world"), logCell, BlockType("minecraft:oak_log"))
            sim.world.placeBlock(WorldId("world"), dirtCell, BlockType("minecraft:dirt"))
            paid.clear()
            red.breaks(dirtCell)
            assertThat(paid).`as`("dirt is not in star_blocks").isEmpty()
            red.breaks(logCell)
            assertThat(paid).`as`("a listed block paid block stars").hasSize(1)
            assertThat(paid.single().amount).isEqualTo(1)
        }
    }

    /**
     * Full N=6 annihilation match with the real bots. Budget = integral of the real `StarEconomy.sourceRatePerSecond` for the two action
     * sources (mining, blocks) over the alive players; minted = the `star-source-mining|blocks` supplies. Prints the measurement.
     */
    @Test
    fun `a full N=6 match mints mining and block stars against the real budget`() {
        MatchDriver.boot(MatchConfig(seed = 101L, botsPerTeam = 6)).use { d ->
            val clock = d.sim.services.getOrThrow<StarSupplyClock>()
            val budget = d.sim.services.getOrThrow<StarBudget>()
            var started: Long? = null
            var cursor = 0
            val minted = HashMap<String, Int>()
            val tokens = HashMap<StarSource, Double>()
            val actions = listOf(StarSource.MINING, StarSource.BLOCKS)
            d.onTick { tick ->
                if (d.ended) return@onTick
                if (started == null) started = d.sim.events.filterIsInstance<MatchStarted>().firstOrNull()?.atTick
                val s0 = started ?: return@onTick
                for (e in d.sim.events.subList(cursor, d.sim.events.size).toList()) {
                    if (e is StarSupplied && e.matchId == d.matchId && e.generatorId.value.startsWith("star-source-")) minted.merge(e.generatorId.value.removePrefix("star-source-"), e.amount, Int::plus)
                }
                cursor = d.sim.events.size
                val sec = (tick - s0) / 20.0
                val econ = clock.economyOf(d.matchId)
                val n = budget.alive(d.matchId)
                for (src in actions) tokens.merge(src, econ.sourceRatePerSecond(src, sec, n) { 0.0 } * (if (src.perPlayer) n else 1) / 20.0, Double::plus)
            }
            d.stepUntil(24000 + 1200) { d.ended || (started?.let { d.tick - it > 24000 } ?: false) }
            val budgetTotal = tokens.values.sum()
            val mintedTotal = actions.sumOf { minted[it.key] ?: 0 }
            System.err.println("SIM-ACTION-STARS minted=$minted budgetTokens=$tokens ratio=${"%.2f".format(mintedTotal / budgetTotal)}")
            assertThat(mintedTotal).`as`("some action star was minted in the full match").isGreaterThan(0)
        }
    }
}
