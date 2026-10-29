package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Match arsenal: tool skills (break_area) treat custom cover blocks by tool class and tier. The matching class chews
 * through a wall, the wrong class barely scratches it, and Mid ore nodes are never removed by an area skill.
 *
 * ADR-0297: one cast is one strike of the skill's `block_damage`, scaled to floor(block_damage * matrix% / 100) by the
 * block's `tool_effectiveness` cell for the tool class and tier. Every number here is read from the shipped content
 * (skill YAML, block spec), so rebalancing content moves the expected cast counts with it.
 */
class ToolSkillCustomBlockScenarioTest {

    private val cooldownGap = 130

    private val contentDir: Path = Paths.get(System.getProperty("astera.sim.contentDir")).toAbsolutePath()

    /** `block_damage` of the skill's break_area op, read from the shipped skill file. */
    private fun blockDamageOf(skill: String): Int {
        val skillText = Files.readString(contentDir.resolve("skills/$skill/skill.yaml"))
        val part = checkNotNull(Regex("""use: ([\w-]+)""").find(skillText)) { "$skill uses no skill part" }.groupValues[1]
        val text = Files.readString(contentDir.resolve("skill-parts/$part.yaml"))
        val match = checkNotNull(Regex("""op: break_area[^\n]*?block_damage: (\d+)""").find(text)) { "$skill has no break_area block_damage" }
        return match.groupValues[1].toInt()
    }

    private fun ScenarioScope.spec(id: String): CustomBlockSpec =
        checkNotNull(sim.services.getOrThrow<ICustomBlockRegistry>().find(BlockType(id))) { "$id is not shipped" }

    /** HP one cast of [skill] removes from [block] with [tool]: floor(block_damage * matrix% / 100). */
    private fun damagePerCast(skill: String, block: CustomBlockSpec, tool: ToolType): Int {
        val percent = checkNotNull(block.toolEffectiveness?.get(tool)) { "${block.id.key} has no tool_effectiveness cell for $tool" }
        return blockDamageOf(skill) * percent / 100
    }

    private fun castsToBreak(hp: Int, perCast: Int): Int = (hp + perCast - 1) / perCast

    @Test
    fun `iron pickaxe bore breaks a stone bulwark after the content-derived cast count but leaves reinforced planks standing`() {
        scenario("tool-skill-custom-block") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val world = caster.world
            val bulwark = spec("astera:stone_bulwark")
            val planks = spec("astera:reinforced_planks")
            val bulwarkAt = Vec3(2.0, groundY + 1, 0.0)
            val planksAt = Vec3(3.0, groundY + 1, 0.0)
            blocks.placeCustomBlock(world, bulwarkAt, bulwark, caster.id)
            blocks.placeCustomBlock(world, planksAt, planks, caster.id)

            val iron = ToolType(ToolKind.PICKAXE, ToolTier.IRON)
            val perCast = damagePerCast("pickaxe-bore-iron", bulwark, iron)
            val casts = castsToBreak(bulwark.baseDurability, perCast)
            assertThat(casts).isGreaterThan(1)
            val chipPerCast = damagePerCast("pickaxe-bore-iron", planks, iron)
            assertThat(chipPerCast * casts).isLessThan(planks.baseDurability)

            repeat(casts - 1) { n ->
                sim.cast(caster, "pickaxe-bore-iron", "iron-pickaxe")
                advance(30)
                // each cast removes exactly one strike's worth; the wall stands until the last cast
                assertThat(blocks.isCover(world, bulwarkAt)).isTrue()
                assertThat(blocks.getDurability(world, bulwarkAt)).isEqualTo(bulwark.baseDurability - perCast * (n + 1))
                advance(cooldownGap)
            }
            sim.cast(caster, "pickaxe-bore-iron", "iron-pickaxe")
            advance(30)
            assertThat(blocks.isCover(world, bulwarkAt)).isFalse()

            // The wrong class chips the planks only a little: still standing, one strike per cast.
            assertThat(blocks.isCover(world, planksAt)).isTrue()
            assertThat(blocks.getDurability(world, planksAt)).isEqualTo(planks.baseDurability - casts * chipPerCast)
        }
    }

    @Test
    fun `a diamond pickaxe bore breaks a stone bulwark after the content-derived cast count`() {
        scenario("tool-skill-diamond-bore") {
            val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val bulwark = spec("astera:stone_bulwark")
            val at = Vec3(2.0, groundY + 1, 0.0)
            blocks.placeCustomBlock(caster.world, at, bulwark, caster.id)

            val perCast = damagePerCast("pickaxe-bore-diamond", bulwark, ToolType(ToolKind.PICKAXE, ToolTier.DIAMOND))
            val casts = castsToBreak(bulwark.baseDurability, perCast)
            assertThat(casts).isGreaterThan(1)
            repeat(casts - 1) {
                sim.cast(caster, "pickaxe-bore-diamond", "diamond-pickaxe")
                advance(30)
                assertThat(blocks.isCover(caster.world, at)).isTrue()
                advance(cooldownGap)
            }
            sim.cast(caster, "pickaxe-bore-diamond", "diamond-pickaxe")
            advance(30)
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
