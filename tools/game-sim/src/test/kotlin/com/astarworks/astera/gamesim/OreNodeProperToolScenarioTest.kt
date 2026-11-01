package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.skillengine.compose.blockProperTools
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.match.application.config.toSpec
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Proper-tool principle for the base ore nodes of the shipped annihilation-arena: timber is an axe node, clay a shovel node, iron a pickaxe
 * node (from content/engine/block-families.yaml). The proper tool lands a full hit and mines the node into its configured drop; a tool of
 * another class is refused (WRONG_TOOL; the shipped blocks have an entry for the proper class only).
 */
class OreNodeProperToolScenarioTest {

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

    private class Case(val block: String, val tool: ToolKind, val proper: String, val wrong: String)

    @Test
    fun `timber is an axe node, clay a shovel node and iron a pickaxe node, the proper tool mines the configured drop and another class is refused`() {
        val properTools = blockProperTools(Paths.get(System.getProperty("astera.sim.contentDir")))
        val cases = listOf(
            Case("astera:ore_timber_node", ToolKind.AXE, "stone-axe", "stone-pickaxe"),
            Case("astera:ore_clay_node", ToolKind.SHOVEL, "stone-shovel", "stone-axe"),
            Case("astera:ore_iron_node", ToolKind.PICKAXE, "stone-pickaxe", "stone-shovel"),
        )
        for (c in cases) assertThat(properTools[c.block]).`as`(c.block).isEqualTo(c.tool)

        scenario("ore-node-proper-tool", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val oreFile = checkNotNull(sim.services.getOrThrow<IOreNodeRegistry>().find("annihilation-arena"))
            val customBlocks = sim.services.getOrThrow<ICustomBlockRegistry>()
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val inventory = checkNotNull(sim.server.view(red.id)).givenItems
            advance(25)

            for (c in cases) {
                val group = oreFile.nodes.first { it.block == c.block }
                val spec = group.toSpec(oreFile, { customBlocks.find(BlockType(it)) }, properTools::get)
                assertThat(spec.requiredTool).`as`("${c.block} classification").isEqualTo(c.tool)
                val cell = spec.cells.first().let { Vec3(it.x + origin.x, it.y, it.z + origin.z) }
                val ore = group.drops.single()

                // Another class is refused: WrongTool, the node keeps all its HP.
                val refused = nodes.mine(red.id, worldId, cell, ItemId(c.wrong))
                assertThat(refused).`as`("${c.block} wrong class").isEqualTo(OreNodeService.Outcome.WrongTool)

                // The proper tool lands a full hit (100 at stone tier, no level bonus), then mines it into the configured drop.
                advance(6)
                val first = nodes.mine(red.id, worldId, cell, ItemId(c.proper))
                assertThat(first).isInstanceOf(OreNodeService.Outcome.Hit::class.java)
                assertThat(spec.maxHp - (first as OreNodeService.Outcome.Hit).hp).`as`("${c.block} proper hit").isEqualTo(100)
                val before = inventory.count { it == ore.itemId }
                var outcome: OreNodeService.Outcome = first
                var swings = 0
                while (outcome !is OreNodeService.Outcome.Mined && swings++ < 20) {
                    advance(6)
                    outcome = nodes.mine(red.id, worldId, cell, ItemId(c.proper))
                }
                assertThat(outcome).`as`("${c.block} mined").isInstanceOf(OreNodeService.Outcome.Mined::class.java)
                assertThat((outcome as OreNodeService.Outcome.Mined).dropped).`as`("${c.block} drop").containsExactly(ore.itemId to ore.count)
                assertThat(inventory.count { it == ore.itemId } - before).`as`("${c.block} delivered").isPositive()
                advance(6)
            }
        }
    }
}
