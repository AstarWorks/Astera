package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Match arsenal: tool skills (break_area) treat custom cover blocks by tool class and tier. The matching class chews
 * through a wall in five iron casts (three diamond casts), the wrong class barely scratches it, and Mid ore nodes
 * are never removed by an area skill.
 */
class ToolSkillCustomBlockScenarioTest {

    private val cooldownGap = 130

    @Test
    fun `iron pickaxe bore breaks a stone bulwark in five casts but leaves reinforced planks standing`() {
        scenario("tool-skill-custom-block") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            val world = caster.world
            val bulwarkAt = Vec3(2.0, groundY + 1, 0.0)
            val planksAt = Vec3(3.0, groundY + 1, 0.0)
            blocks.placeCustomBlock(world, bulwarkAt, checkNotNull(registry.find(BlockType("astera:stone_bulwark"))), caster.id)
            blocks.placeCustomBlock(world, planksAt, checkNotNull(registry.find(BlockType("astera:reinforced_planks"))), caster.id)

            sim.cast(caster, "pickaxe-bore-iron", "iron-pickaxe")
            assertThat(blocks.getDurability(world, bulwarkAt)).isEqualTo(600)
            advance(30)
            // One cast hurts the bulwark (600 HP) but does not break it.
            assertThat(blocks.isCover(world, bulwarkAt)).isTrue()
            assertThat(blocks.getDurability(world, bulwarkAt)).isLessThan(600)

            repeat(4) {
                advance(cooldownGap)
                sim.cast(caster, "pickaxe-bore-iron", "iron-pickaxe")
                advance(30)
            }
            assertThat(blocks.isCover(world, bulwarkAt)).isFalse()

            // The wrong class chips the planks (450 HP) only a little: still standing after the same five casts.
            assertThat(blocks.isCover(world, planksAt)).isTrue()
            assertThat(blocks.getDurability(world, planksAt)).isGreaterThan(450 / 2)
        }
    }

    @Test
    fun `a diamond pickaxe bore breaks a stone bulwark in three casts`() {
        scenario("tool-skill-diamond-bore") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
            val at = Vec3(2.0, groundY + 1, 0.0)
            blocks.placeCustomBlock(caster.world, at, checkNotNull(registry.find(BlockType("astera:stone_bulwark"))), caster.id)

            sim.cast(caster, "pickaxe-bore-diamond", "diamond-pickaxe")
            assertThat(blocks.isCover(caster.world, at)).isTrue()
            advance(30)
            assertThat(blocks.isCover(caster.world, at)).isTrue()
            repeat(2) {
                advance(100)
                sim.cast(caster, "pickaxe-bore-diamond", "diamond-pickaxe")
                advance(30)
            }
            assertThat(blocks.isCover(caster.world, at)).isFalse()
        }
    }

    @Test
    fun `break_area never removes an ore node`() {
        scenario("tool-skill-ore-node") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val blue = player("blue", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(caster to "red", blue to "blue"))
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles"))?.origin ?: Vec3(0.0, 0.0, 0.0)

            // The iron node of siege-isles sits at stage-relative (5, 65, 5); a plain stone block one cell nearer is the control.
            val nodeAt = Vec3(origin.x + 5, 65.0, origin.z + 5)
            val controlAt = Vec3(origin.x + 4, 65.0, origin.z + 5)
            val ore = BlockType("minecraft:iron_ore")
            val stone = BlockType("minecraft:stone")
            sim.world.placeBlock(worldId, nodeAt, ore)
            sim.world.placeBlock(worldId, controlAt, stone)

            caster.world = worldId
            caster.position = Vec3(origin.x + 1, 65.0, origin.z + 5)
            caster.direction = Vec3(1.0, 0.0, 0.0)
            sim.cast(caster, "pickaxe-bore-diamond", "diamond-pickaxe")
            advance(30)

            assertThat(sim.world.blockAt(worldId, controlAt)).isNull()
            assertThat(sim.world.blockAt(worldId, nodeAt)).isEqualTo(ore)

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
        }
    }
}
