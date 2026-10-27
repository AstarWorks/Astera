package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase.Outcome
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Match arsenal: bare-hand mining is far slower than the right tool, and break_area respects the block-edit guard. */
class ArsenalToolsScenarioTest {

    private val world = WorldId("arenas")

    @Test
    fun `bare hand mines a wall much slower than a pickaxe`() {
        scenario("arsenal-bare-hand") {
            val miner = player("miner", team = "red")
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            val hitter = sim.services.getOrThrow<HitCustomBlockUseCase>()

            fun swingsToBreak(tool: String?, x: Double): Int {
                val at = Vec3(x, groundY + 1, 10.0)
                val spec = checkNotNull(registry.find(BlockType("astera:reinforced_wall")))
                assertThat(blocks.placeCustomBlock(world, at, spec, miner.id)).isInstanceOf(Result.Success::class.java)
                for (n in 1..200) {
                    advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
                    val out = (hitter.hit(miner.id, world, at, tool?.let(::ItemId)) as Result.Success).value
                    if (out is Outcome.Broken) return n
                }
                error("never broke")
            }

            val withTool = swingsToBreak("iron-pickaxe", 0.0)
            val bare = swingsToBreak(null, 2.0)
            assertThat(bare).isGreaterThanOrEqualTo(withTool * 5)
        }
    }

    @Test
    fun `stone bore cracks a mixed wall and the second cast finishes stone`() {
        scenario("arsenal-mixed-hp") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val stoneAt = Vec3(2.0, groundY + 1, 0.0)
            val dirtAt = Vec3(2.0, groundY + 1, 1.0)
            val woolAt = Vec3(3.0, groundY + 1, 0.0)
            val obsidianAt = Vec3(3.0, groundY + 1, 1.0)
            sim.world.placeBlock(caster.world, stoneAt, BlockType("minecraft:stone"))
            sim.world.placeBlock(caster.world, dirtAt, BlockType("minecraft:dirt"))
            sim.world.placeBlock(caster.world, woolAt, BlockType("minecraft:white_wool"))
            sim.world.placeBlock(caster.world, obsidianAt, BlockType("minecraft:obsidian"))
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            sim.cast(caster, "pickaxe-bore-stone", "stone-pickaxe")
            advance(30)
            assertThat(sim.world.blockAt(caster.world, dirtAt)).isNull()
            assertThat(sim.world.blockAt(caster.world, woolAt)).isNull()
            assertThat(sim.world.blockAt(caster.world, stoneAt)).isEqualTo(BlockType("minecraft:stone"))
            assertThat(blocks.getDurability(caster.world, stoneAt)).isEqualTo(50)
            assertThat(eventsOf<com.astarworks.astera.core.domain.event.BlockBreakStageChanged>().last { it.position == stoneAt }.newStage)
                .isEqualTo(com.astarworks.astera.core.domain.animation.block.BlockBreakStage.STAGE_6)
            advance(130)
            sim.cast(caster, "pickaxe-bore-stone", "stone-pickaxe")
            advance(30)
            assertThat(sim.world.blockAt(caster.world, stoneAt)).isNull()
            repeat(3) {
                advance(130)
                sim.cast(caster, "pickaxe-bore-stone", "stone-pickaxe")
                advance(30)
            }
            assertThat(sim.world.blockAt(caster.world, obsidianAt)).isEqualTo(BlockType("minecraft:obsidian"))
        }
    }

    @Test
    fun `iron bore finishes plain stone in one cast`() {
        scenario("arsenal-iron-hp") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val at = Vec3(2.0, groundY + 1, 0.0)
            sim.world.placeBlock(caster.world, at, BlockType("minecraft:stone"))
            sim.cast(caster, "pickaxe-bore-iron", "iron-pickaxe")
            assertThat(sim.world.blockAt(caster.world, at)).isNotNull()
            advance(30)
            assertThat(sim.world.blockAt(caster.world, at)).isNull()
        }
    }

    @Test
    fun `two players accumulate bore damage on the same block`() {
        scenario("arsenal-shared-hp") {
            val a = player("a", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val b = player("b", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val at = Vec3(2.0, groundY + 1, 0.0)
            sim.world.placeBlock(a.world, at, BlockType("minecraft:stone"))
            sim.cast(a, "pickaxe-bore-stone", "stone-pickaxe")
            advance(30)
            assertThat(sim.world.blockAt(a.world, at)).isNotNull()
            sim.cast(b, "pickaxe-bore-stone", "stone-pickaxe")
            advance(30)
            assertThat(sim.world.blockAt(a.world, at)).isNull()
        }
    }

    @Test
    fun `periodic GC returns terrain and cover damage store to zero and a later bore starts fresh`() {
        scenario("arsenal-idle-gc", standaloneTeams = false) {
            val caster = player("caster", team = "red")
            val enemy = player("enemy", team = "blue")
            val match = startMatch("carry-tnt", mapOf(caster to "red", enemy to "blue"))
            val matchWorld = checkNotNull(sim.services.getOrThrow<com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository>().findByMatch(match)).worldId
            caster.world = matchWorld
            caster.position = Vec3(0.0, groundY, 0.0)
            caster.direction = Vec3(1.0, 0.0, 0.0)
            val at = Vec3(2.0, groundY + 1, 0.0)
            val coverAt = Vec3(3.0, groundY + 1, 0.0)
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            sim.world.placeBlock(matchWorld, at, BlockType("minecraft:stone"))
            blocks.placeCustomBlock(matchWorld, coverAt, checkNotNull(registry.find(BlockType("astera:stone_bulwark"))), caster.id)
            assertThat(blocks.tracked(matchWorld)).isEmpty()
            sim.cast(caster, "pickaxe-bore-stone", "stone-pickaxe")
            advance(30)
            assertThat(blocks.tracked(matchWorld).keys).contains(at, coverAt)
            advance(221)
            assertThat(blocks.tracked(matchWorld)).isEmpty()
            assertThat(blocks.getDurability(matchWorld, coverAt)).isEqualTo(600)
            assertThat(blocks.damagedWorlds()).doesNotContain(matchWorld)
            for (cell in listOf(at, coverAt)) {
                assertThat(eventsOf<com.astarworks.astera.core.domain.event.BlockBreakStageChanged>().last { it.position == cell }.newStage)
                    .isEqualTo(com.astarworks.astera.core.domain.animation.block.BlockBreakStage.RESET)
            }
            sim.cast(caster, "pickaxe-bore-stone", "stone-pickaxe")
            advance(30)
            assertThat(sim.world.blockAt(matchWorld, at)).isEqualTo(BlockType("minecraft:stone"))
            assertThat(blocks.getDurability(matchWorld, at)).isEqualTo(50)
            sim.services.getOrThrow<com.astarworks.astera.match.application.port.inbound.IMatchLifecycle>()
                .end(match, com.astarworks.astera.core.domain.event.EndReason.AdminEnded)
            advance(2)
            assertThat(blocks.tracked(matchWorld)).isEmpty()
        }
    }

    @Test
    fun `break_area removes breakable blocks inside the arena and leaves bedrock-class and outside cells`() {
        scenario("arsenal-break-area") {
            val caster = player("caster", team = "red")
            val centre = Vec3(4.0, groundY + 1, 4.0)
            val stone = BlockType("minecraft:stone")
            val dirt = BlockType("minecraft:dirt")
            val obsidian = BlockType("minecraft:obsidian")
            sim.world.placeBlock(caster.world, centre, stone)
            sim.world.placeBlock(caster.world, centre + Vec3(1.0, 0.0, 0.0), dirt)
            sim.world.placeBlock(caster.world, centre + Vec3(0.0, 0.0, 1.0), obsidian)

            val dispatcher = checkNotNull(sim.combat.mechanicDispatcher)
            fun run(who: SimEntity, m: Mechanic) = dispatcher.dispatch(
                listOf(m),
                MechanicContext(actorId = who.id, targetId = null, origin = centre, worldId = who.world, sourceItemId = ItemId("stone-pickaxe")),
            )

            // Power 1 breaks soil and stone, not obsidian. Sphere centred at the origin (pose is absent: length 0 look +X).
            val sphere = Mechanic.BreakArea(shape = Mechanic.AreaShape.SPHERE, radius = 2.0, length = 0.0)
            run(caster, sphere)
            assertThat(sim.world.blockAt(caster.world, centre)).isEqualTo(stone)
            advance(30)
            run(caster, sphere)
            advance(30)
            assertThat(sim.world.blockAt(caster.world, centre)).isNull()
            assertThat(sim.world.blockAt(caster.world, centre + Vec3(1.0, 0.0, 0.0))).isNull()
            assertThat(sim.world.blockAt(caster.world, centre + Vec3(0.0, 0.0, 1.0))).isEqualTo(obsidian)

            // A caster who is not in a match has no arena: the guard refuses every cell.
            val outsider = player("outsider")
            sim.world.placeBlock(outsider.world, centre, stone)
            run(outsider, sphere)
            assertThat(sim.world.blockAt(outsider.world, centre)).isEqualTo(stone)
        }
    }
}
