package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase.Outcome
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.event.BlockBreakStageChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0090 field tools against the shipped cover blocks: the right tool breaks a block in fewer swings than the
 * wrong one, a block with HP survives until its last swing, the wrong tool does nothing at all, the crack stage
 * deepens with every hit, and the match kit carries all three tools.
 */
class CustomBlockToolScenarioTest {

    private val world = WorldId("arenas")

    @Test
    fun `right tool breaks cover in few swings and the wrong tool never does`() {
        scenario("custom-block-tools") {
            val miner = player("miner", team = "red")
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            val hitter = sim.services.getOrThrow<HitCustomBlockUseCase>()

            fun place(id: String, x: Double): Vec3 {
                val at = Vec3(x, groundY + 1, 10.0)
                val spec = checkNotNull(registry.find(BlockType(id))) { "$id is not shipped" }
                assertThat(blocks.placeCustomBlock(world, at, spec, miner.id)).isInstanceOf(Result.Success::class.java)
                return at
            }

            fun swing(tool: String, at: Vec3): Outcome {
                advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
                return (hitter.hit(miner.id, world, at, ItemId(tool)) as Result.Success).value
            }

            /** Swings until the block is gone (or [limit] swings pass); returns the swing count. */
            fun swingsToBreak(tool: String, at: Vec3, limit: Int): Int? {
                for (n in 1..limit) if (swing(tool, at) is Outcome.Broken) return n
                return null
            }

            // Reinforced wall (stone, 400 HP): pickaxe 100/hit -> exactly 4 swings; the first three leave it standing.
            val wall = place("astera:reinforced_wall", 0.0)
            repeat(3) { assertThat(swing("field-pickaxe", wall)).isInstanceOf(Outcome.Chipped::class.java) }
            assertThat(blocks.tracked(world)[wall]?.durability).isEqualTo(100)
            assertThat(swing("field-pickaxe", wall)).isInstanceOf(Outcome.Broken::class.java)

            // The axe is the wrong tool for stone: 25/hit, so 16 swings. The shovel does nothing.
            val wallAxe = place("astera:reinforced_wall", 2.0)
            assertThat(swingsToBreak("field-axe", wallAxe, 30)).isEqualTo(16)
            val wallShovel = place("astera:reinforced_wall", 4.0)
            assertThat(swingsToBreak("field-shovel", wallShovel, 30)).isNull()
            assertThat(swing("field-shovel", wallShovel)).isEqualTo(Outcome.WrongTool)
            assertThat(blocks.tracked(world)[wallShovel]?.durability).isEqualTo(400)

            // Barricade (wood, 300 HP): the axe needs 3 swings, the pickaxe 12.
            assertThat(swingsToBreak("field-axe", place("astera:barricade", 6.0), 30)).isEqualTo(3)
            assertThat(swingsToBreak("field-pickaxe", place("astera:barricade", 8.0), 30)).isEqualTo(12)

            // Sandbags (soil, 200 HP): the shovel needs 2 swings; pickaxe and axe are useless.
            assertThat(swingsToBreak("field-shovel", place("astera:sandbags", 10.0), 30)).isEqualTo(2)
            assertThat(swingsToBreak("field-pickaxe", place("astera:sandbags", 12.0), 30)).isNull()
            assertThat(swingsToBreak("field-axe", place("astera:sandbags", 14.0), 30)).isNull()

            // Hardened soil (500 HP) wants the pickaxe: 5 swings; the shovel scratches (25/hit) -> 20.
            assertThat(swingsToBreak("field-pickaxe", place("astera:hardened_soil", 16.0), 30)).isEqualTo(5)
            assertThat(swingsToBreak("field-shovel", place("astera:hardened_soil", 18.0), 30)).isEqualTo(20)
        }
    }

    @Test
    fun `crack stages deepen with every hit and clear when the block breaks`() {
        scenario("custom-block-crack-stages") {
            val miner = player("miner", team = "red")
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            val hitter = sim.services.getOrThrow<HitCustomBlockUseCase>()
            val at = Vec3(0.0, groundY + 1, 10.0)
            blocks.placeCustomBlock(world, at, checkNotNull(registry.find(BlockType("astera:reinforced_wall"))), miner.id)

            val stages = mutableListOf<BlockBreakStage>()
            repeat(3) {
                advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
                val outcome = (hitter.hit(miner.id, world, at, ItemId("field-pickaxe")) as Result.Success).value
                stages += (outcome as Outcome.Chipped).stage
            }
            // 400 HP, 100 per swing: 25 %, 50 %, 75 % lost.
            assertThat(stages).containsExactly(BlockBreakStage.STAGE_2, BlockBreakStage.STAGE_4, BlockBreakStage.STAGE_6)
            assertThat(stages).isSortedAccordingTo(compareBy { it.ordinal })

            advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
            hitter.hit(miner.id, world, at, ItemId("field-pickaxe"))
            val shown = eventsOf<BlockBreakStageChanged>().filter { it.position == at }.map { it.newStage }
            assertThat(shown).containsExactly(
                BlockBreakStage.STAGE_2, BlockBreakStage.STAGE_4, BlockBreakStage.STAGE_6, BlockBreakStage.RESET,
            )
        }
    }

    @Test
    fun `swings inside the cooldown are ignored`() {
        scenario("custom-block-swing-guards") {
            val miner = player("miner", team = "red")
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            val hitter = sim.services.getOrThrow<HitCustomBlockUseCase>()
            val at = Vec3(0.0, groundY + 1, 10.0)
            blocks.placeCustomBlock(world, at, checkNotNull(registry.find(BlockType("astera:barricade"))), miner.id)

            assertThat((hitter.hit(miner.id, world, at, ItemId("field-axe")) as Result.Success).value).isInstanceOf(Outcome.Chipped::class.java)
            assertThat((hitter.hit(miner.id, world, at, ItemId("field-axe")) as Result.Success).value).isEqualTo(Outcome.TooFast)
            assertThat(blocks.tracked(world)[at]?.durability).isEqualTo(200)
        }
    }

    @Test
    fun `the match kit carries a pickaxe, an axe and a shovel`() {
        scenario("custom-block-tool-kit") {
            val items = sim.services.getOrThrow<IItemRegistry>()
            for (id in listOf("stone-pickaxe", "stone-axe", "stone-shovel")) {
                val spec = checkNotNull(items.find(ItemId(id))) { "$id is not shipped" }
                assertThat(spec.pdcTags["astera:tool"]).endsWith("/STONE")
            }
        }
    }
}
