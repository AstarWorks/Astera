package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.skillengine.compose.blockProperTools
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.economy.application.service.MatchShopLoaderService
import com.astarworks.astera.economy.domain.matchshop.MatchShopTab
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Timber and clay base nodes of the shipped annihilation-arena drop their own match currency (ore-timber / ore-clay), the cheap shop rung
 * (blocks, smoke, grappling hook) is priced in them, and iron stays the currency of weapons and armour. The bot planner digs only the nodes
 * whose proper tool is the pickaxe, so it never plans a wrong-tool dig on a timber / clay node.
 */
class OreDropsShopScenarioTest {

    private val config = AnnihilationConfig(
        pillarHp = 1000,
        buildPhaseTicks = 4000,
        engagePhaseTicks = 200,
        centerPhaseTicks = 200,
        escalationPhaseTicks = 200,
        suddenDeathTicks = 400,
        bleedIntervalTicks = 20,
        bleedPercent = 10,
        bleedFloorPercent = 10,
    )

    @Test
    fun `mining timber with an axe gives logs, mining clay with a shovel gives clay, and the logs and clay buy the cheap rung at the shop`() {
        scenario("ore-drops-shop", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val oreFile = checkNotNull(sim.services.getOrThrow<IOreNodeRegistry>().find("annihilation-arena"))
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val inventory = checkNotNull(sim.server.view(red.id)).givenItems
            advance(25)

            fun mineOut(block: String, tool: String, index: Int) {
                val p = oreFile.nodes.first { it.block == block }.positions[index]
                val cell = Vec3(p.x + origin.x, p.y, p.z + origin.z)
                var outcome: OreNodeService.Outcome = OreNodeService.Outcome.Cooldown
                var swings = 0
                while (outcome !is OreNodeService.Outcome.Mined && swings++ < 30) {
                    advance(6)
                    outcome = nodes.mine(red.id, worldId, cell, ItemId(tool))
                }
                assertThat(outcome).`as`("$block #$index mined with $tool").isInstanceOf(OreNodeService.Outcome.Mined::class.java)
            }

            // Two timber nodes (3 logs each) and two clay nodes (3 clay each), each with its proper tool.
            mineOut("astera:ore_timber_node", "stone-axe", 0)
            mineOut("astera:ore_timber_node", "stone-axe", 1)
            mineOut("astera:ore_clay_node", "stone-shovel", 0)
            mineOut("astera:ore_clay_node", "stone-shovel", 1)
            assertThat(inventory.count { it == "ore-timber" }).isEqualTo(6)
            assertThat(inventory.count { it == "ore-clay" }).isEqualTo(6)
            assertThat(inventory.count { it == "ore-iron" }).`as`("no iron from timber / clay nodes").isZero()

            // The cheap rung is priced in them (wool-block 4 timber, hardened-soil-block 4 clay); weapons and armour stay in iron / gold.
            val loader = MatchShopLoaderService()
            assertThat(loader.loadFrom(GameSim.defaultContentDir().resolve("shops")).failed).isZero()
            val blocks = loader.catalog.tab(MatchShopTab.BLOCKS)
            fun buy(item: String) {
                val index = blocks.indexOfFirst { it.itemId.value == item }
                assertThat(index).`as`("$item is a blocks-tab offer").isNotNegative()
                sim.services.getOrThrow<IGuiIntentRouter>().dispatch(
                    checkNotNull(sim.server.view(red.id)), GuiIntent("matchshop.buy", mapOf("tab" to "blocks", "index" to index.toString())),
                )
            }
            assertThat(blocks.single { it.itemId.value == "wool-block" }.price.mapKeys { it.key.value }).isEqualTo(mapOf("ore-timber" to 4))
            assertThat(blocks.single { it.itemId.value == "hardened-soil-block" }.price.mapKeys { it.key.value }).isEqualTo(mapOf("ore-clay" to 4))
            buy("wool-block")
            assertThat(inventory.count { it == "wool-block" }).isPositive()
            assertThat(inventory.count { it == "ore-timber" }).`as`("4 timber paid").isEqualTo(2)
            buy("hardened-soil-block")
            assertThat(inventory.count { it == "hardened-soil-block" }).isPositive()
            assertThat(inventory.count { it == "ore-clay" }).`as`("4 clay paid").isEqualTo(2)

            // Not enough left (2 of 4): refused, nothing moves.
            val woolBefore = inventory.count { it == "wool-block" }
            buy("wool-block")
            assertThat(inventory.count { it == "wool-block" }).isEqualTo(woolBefore)
            assertThat(inventory.count { it == "ore-timber" }).isEqualTo(2)

            // Iron still buys armour and upgrades: none is priced in timber / clay. In the weapons tab only the two basic utilities
            // (grappling-hook, smoke-bomb) sit on the cheap rung; every other weapon stays in iron / gold / diamond / emerald.
            for (tab in listOf(MatchShopTab.ARMOR, MatchShopTab.UPGRADES)) {
                assertThat(loader.catalog.tab(tab).flatMap { it.price.keys.map { k -> k.value } }).`as`("$tab currencies")
                    .doesNotContain("ore-timber", "ore-clay")
            }
            val cheapWeapons = loader.catalog.tab(MatchShopTab.WEAPONS)
                .filter { o -> o.price.keys.any { it.value == "ore-timber" || it.value == "ore-clay" } }.map { it.itemId.value }
            assertThat(cheapWeapons).containsExactlyInAnyOrder("grappling-hook", "smoke-bomb")
        }
    }

    @Test
    fun `the bot planner digs only pickaxe nodes, never a timber or clay node with the wrong tool`() {
        val contentDir = GameSim.defaultContentDir()
        val objectives = checkNotNull(StageObjectives.load(contentDir.toFile(), "annihilation")) { "annihilation stage did not load" }
        val file = checkNotNull(sim0Registry(contentDir.toFile()).find("annihilation-arena"))
        val properTools = blockProperTools(Paths.get(System.getProperty("astera.sim.contentDir")))
        val pickaxeCells = file.nodes.filter { properTools[it.block] == ToolKind.PICKAXE }.flatMap { g -> g.positions.map { it.x to it.z } }.toSet()
        val otherCells = file.nodes.filter { properTools[it.block] != ToolKind.PICKAXE }.flatMap { g -> g.positions.map { it.x to it.z } }.toSet()
        assertThat(otherCells).`as`("timber / clay / heartwood / silt nodes exist").isNotEmpty()
        val planned = listOf("red", "blue", "yellow").flatMap { team -> checkNotNull(objectives.economySites(team)).nodes }
            .map { (it.x - 0.5) to (it.z - 0.5) }.toSet()
        assertThat(planned).`as`("planner nodes").isNotEmpty()
        assertThat(planned.filter { it in otherCells }).`as`("planned digs on non-pickaxe nodes").isEmpty()
        assertThat(pickaxeCells).containsAll(planned)
    }

    private fun sim0Registry(contentDir: java.io.File): com.astarworks.astera.match.application.contents.OreNodeContents {
        val contents = com.astarworks.astera.match.application.contents.OreNodeContents()
        check(contents.bootstrap(contentDir.toPath().resolve("ore-nodes")).failed == 0)
        return contents
    }
}
