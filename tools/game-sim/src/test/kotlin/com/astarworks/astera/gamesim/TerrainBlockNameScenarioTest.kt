package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.BlockStateNames
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.gamesim.playable.SimTerrainLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TerrainView.blockName: sim side returns the placed block's id; the protocol side's state-id table maps known ids to names. */
class TerrainBlockNameScenarioTest {
    @Test
    fun `sim terrain names placed blocks and the protocol table maps state ids`() {
        val sim = GameSim.boot("terrain-block-name")
        try {
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val terrain = SimTerrainLoader.load(sim, stage)
            val view = terrain.view
            val x = terrain.plan.minX + 1
            val z = terrain.plan.minZ + 1
            val y = terrain.plan.surfaceY + 40
            listOf("minecraft:barrel", "minecraft:chest", "minecraft:crafting_table").forEachIndexed { i, key ->
                sim.world.placeBlock(stage.world, Vec3((x + i).toDouble(), y.toDouble(), z.toDouble()), BlockType(key))
            }
            assertThat(view.blockName(x, y, z)).isEqualTo("minecraft:barrel")
            assertThat(view.blockName(x + 1, y, z)).isEqualTo("minecraft:chest")
            assertThat(view.blockName(x + 2, y, z)).isEqualTo("minecraft:crafting_table")
            assertThat(view.blockName(x + 3, y, z)).isEqualTo("minecraft:air")
            assertThat(view.blockName(terrain.plan.maxX + 1000, y, z)).isNull()

            // Paper 26.3 data report: chest = 5543..5566, crafting_table = 6866, barrel = 24099..24110, air = 0.
            assertThat(BlockStateNames.nameOf(0)).isEqualTo("minecraft:air")
            assertThat(BlockStateNames.nameOf(5543)).isEqualTo("minecraft:chest")
            assertThat(BlockStateNames.nameOf(5566)).isEqualTo("minecraft:chest")
            assertThat(BlockStateNames.nameOf(6866)).isEqualTo("minecraft:crafting_table")
            assertThat(BlockStateNames.nameOf(24100)).isEqualTo("minecraft:barrel")
            assertThat(BlockStateNames.nameOf(9_999_999)).isNull()
        } finally {
            sim.shutdown()
        }
    }
}
