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
    fun `vanilla stone survives the cast tick and diamond mines faster than iron and stone`() {
        scenario("arsenal-timed-tier-mining") {
            val caster = player("caster", team = "red")
            val at = Vec3(2.0, groundY + 1, 0.0)
            val stone = BlockType("minecraft:stone")
            val dispatcher = checkNotNull(sim.combat.mechanicDispatcher)
            val times = listOf("stone", "iron", "diamond").associateWith { tier ->
                sim.world.placeBlock(caster.world, at, stone)
                dispatcher.dispatch(
                    listOf(Mechanic.BreakArea(shape = Mechanic.AreaShape.SPHERE, radius = 0.0, length = 0.0, power = 1.0, toolClass = "PICKAXE")),
                    MechanicContext(actorId = caster.id, targetId = null, origin = at - Vec3(0.0, 1.62, 0.0), worldId = caster.world, sourceItemId = ItemId("$tier-pickaxe")),
                )
                assertThat(sim.world.blockAt(caster.world, at)).isEqualTo(stone)
                var ticks = 0
                while (sim.world.blockAt(caster.world, at) == stone && ticks < 30) {
                    advance(1)
                    ticks++
                }
                assertThat(sim.world.blockAt(caster.world, at)).isNull()
                ticks
            }
            assertThat(times.getValue("diamond")).isLessThan(times.getValue("iron"))
            assertThat(times.getValue("iron")).isLessThan(times.getValue("stone"))
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
            val sphere = Mechanic.BreakArea(shape = Mechanic.AreaShape.SPHERE, radius = 2.0, length = 0.0, power = 1.0)
            run(caster, sphere)
            assertThat(sim.world.blockAt(caster.world, centre)).isEqualTo(stone)
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
