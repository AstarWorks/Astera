package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyToolBay
import com.astarworks.astera.core.application.service.lobby.IToolBayWorld
import com.astarworks.astera.core.application.service.lobby.LobbyToolBayService
import com.astarworks.astera.core.application.service.lobby.LobbyWorldContent
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.lobby.LobbyBlockPos
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * MAP-A6: the lobby tool test bay - stone / dirt / wood blocks (incl. the three Annihilation pillar materials), a rack
 * of every shop tool, breaks journaled and restored after 10 s idle or by the reset button, nothing editable outside.
 */
class LobbyToolBayScenarioTest {

    private val lobby = WorldId("world")

    /** The generated lobby as a block grid with overrides, standing in for the Paper world. */
    private class GridWorld(private val generated: Map<LobbyBlockPos, String>) : IToolBayWorld {
        val edits = HashMap<LobbyBlockPos, String>()
        override fun stateAt(x: Int, y: Int, z: Int): String =
            LobbyBlockPos(x, y, z).let { edits[it] ?: generated[it] ?: "minecraft:air" }
        override fun setState(x: Int, y: Int, z: Int, state: String) { edits[LobbyBlockPos(x, y, z)] = state }
        fun break_(x: Int, y: Int, z: Int) = setState(x, y, z, "minecraft:air")
    }

    @Test
    fun `the tool bay has pillar-material blocks and a full tool rack, breaks are restored after 10 s or by the reset button, and the rest of the lobby is protected`() {
        scenario("lobby-tool-bay") {
            val config = (LobbyWorldContent.load(Paths.get(System.getProperty("astera.sim.contentDir") ?: "content").toAbsolutePath().normalize()) as Result.Success).value
            val build = LobbyWorldContent.plan(config, listOf("annihilation"))
            val bayPlan = build.toolBay
            assertThat(config.markerVersion).describedAs("the bay needs a repaste").isGreaterThanOrEqualTo(7)
            assertThat(build.isWalkable(bayPlan.entry)).describedAs("the gate is walkable").isTrue()

            // the bay holds stone-, dirt- and wood-type blocks including the three Annihilation pillar materials
            val region = bayPlan.region
            val inBay = build.blocks.filter { (p, _) -> region.contains(p.x + 0.5, p.y + 0.5, p.z + 0.5) }.values
            assertThat(inBay).contains("minecraft:polished_deepslate", "minecraft:coarse_dirt", "minecraft:stripped_dark_oak_wood[axis=y]")
            assertThat(inBay.any { it == "minecraft:stone" }).isTrue()
            assertThat(inBay.any { it == "minecraft:dirt" }).isTrue()
            assertThat(inBay.any { it.endsWith("_log[axis=y]") }).isTrue()
            assertThat(build.blocks[bayPlan.resetButton]).startsWith("minecraft:stone_button")

            val grid = GridWorld(build.blocks)
            val bay = LobbyToolBayService(lobby, bayPlan, grid, sim.scheduler)
            sim.services.register<ILobbyToolBay>(bay, priority = 0)
            bay.start()

            // the rack shows nine tools: pickaxe / shovel / axe x stone / iron / diamond
            advance(120)
            val rack = checkNotNull(sim.items.trainingRack)
            assertThat(sim.trainingRack.toolFrames.map { it.itemId.value }).containsExactly(
                "stone-pickaxe", "iron-pickaxe", "diamond-pickaxe",
                "stone-shovel", "iron-shovel", "diamond-shovel",
                "stone-axe", "iron-axe", "diamond-axe",
            )
            val visitor = player("visitor", at = Vec3(bayPlan.area.minX + 1.5, bayPlan.entry.y, bayPlan.entry.z)) // inside the yard: the gate cell itself lies just outside the claim area
            visitor.world = lobby
            val claim = rack.claim(visitor.id, ItemId("iron-pickaxe"))
            assertThat(claim.isSuccess).describedAs("claim from the tool rack in the bay: $claim").isTrue()

            // hand-mining (policy true, journaled) and a skill edit both come back once the bay has been idle 10 s
            val stone = bayPlan.region.let { LobbyBlockPos(it.minX.toInt() + 1, it.minY.toInt(), it.minZ.toInt()) }
            val wood = build.blocks.entries.first { (p, s) -> s.startsWith("minecraft:stripped_dark_oak_wood") && region.contains(p.x + 0.5, p.y + 0.5, p.z + 0.5) }.key
            val air = LobbyBlockPos(region.minX.toInt() + 1, region.minY.toInt(), (region.minZ.toInt() + region.maxZ.toInt()) / 2)
            val originals = listOf(stone, wood, air).associateWith { grid.stateAt(it.x, it.y, it.z) }
            assertThat(bay.editPolicy(lobby, stone.x, stone.y, stone.z)).isTrue()
            for (p in listOf(stone, wood)) { bay.remember(lobby, p.x, p.y, p.z); grid.break_(p.x, p.y, p.z) }
            bay.remember(lobby, air.x, air.y, air.z); grid.setState(air.x, air.y, air.z, "minecraft:stone") // a skill-placed block
            assertThat(bay.pending()).isEqualTo(3)
            advance(100)
            assertThat(grid.stateAt(wood.x, wood.y, wood.z)).describedAs("not restored while recently changed").isEqualTo("minecraft:air")
            advance(150)
            originals.forEach { (p, state) -> assertThat(grid.stateAt(p.x, p.y, p.z)).describedAs("restored $p").isEqualTo(state) }
            assertThat(bay.pending()).isZero()

            // the reset button restores at once
            bay.remember(lobby, wood.x, wood.y, wood.z); grid.break_(wood.x, wood.y, wood.z)
            assertThat(bay.reset()).isEqualTo(1)
            advance(2)
            assertThat(grid.stateAt(wood.x, wood.y, wood.z)).isEqualTo(originals.getValue(wood))

            // outside the bay the lobby is protected (false); arena slots are not the lobby (null)
            val spawn = build.spawn
            assertThat(bay.editPolicy(lobby, spawn.x.toInt(), build.surfaceY, spawn.z.toInt())).isFalse()
            assertThat(bay.editPolicy(lobby, region.minX.toInt() - 3, build.surfaceY + 1, region.minZ.toInt())).isFalse()
            assertThat(bay.editPolicy(lobby, region.minX.toInt(), build.surfaceY, region.minZ.toInt())).describedAs("the floor is not editable").isFalse()
            assertThat(bay.editPolicy(lobby, 5000, 64, 5000)).isNull()
            bay.remember(lobby, spawn.x.toInt(), build.surfaceY, spawn.z.toInt())
            assertThat(bay.pending()).describedAs("nothing outside the bay is journaled").isZero()
        }
    }
}
