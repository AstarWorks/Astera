package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import java.nio.file.Files
import java.nio.file.Paths
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.usecase.block.BlockDamageGcUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0280 / ADR-0297: cover damage from skills, one strike per cast. A gun/spell chips cover only through its `block_damage` field, the field
 * tools' skills do the large-scale breaking and placing, and a finished match rebuilds all of it.
 */
class SkillCoverDamageScenarioTest {

    private val world = WorldId("arenas")

    /** A cover block of [id] at [cell], known to the gateway tracker and (for projectile contact) to the sim world. */
    private fun ScenarioScope.cover(id: String, cell: Vec3, inWorld: WorldId = world, shooter: SimEntity) {
        val blocks = sim.services.getOrThrow<IBlockStateGateway>()
        val registry = sim.services.getOrThrow<ICustomBlockRegistry>()
        val spec = checkNotNull(registry.find(BlockType(id))) { "$id is not shipped" }
        assertThat(blocks.placeCustomBlock(inWorld, cell, spec, shooter.id)).isInstanceOf(Result.Success::class.java)
        sim.world.placeBlock(inWorld, cell, BlockType(id))
    }

    private fun ScenarioScope.hp(cell: Vec3, inWorld: WorldId = world): Int? =
        sim.services.getOrThrow<IBlockStateGateway>().getDurability(inWorld, cell)

    @Test
    fun `a weapon skill with block_damage chips cover where its bullet lands`() {
        scenario("skill-cover-chip") {
            val shooter = player("shooter", at = Vec3(0.5, groundY, 0.0), team = "red")
            shooter.world = world
            val wall = Vec3(0.0, groundY + 1, 6.0)
            cover("astera:reinforced_wall", wall, shooter = shooter)

            shooter.holds("pistol")
            shooter.rightClick()
            advance(20)

            // pistol-shot: block_damage amount 12 (flat, tool-independent) on a 400 HP wall.
            assertThat(hp(wall)).isEqualTo(388)
        }
    }

    @Test
    fun `a tool bore lands its whole block_damage on cover in one strike per cast`() {
        scenario("skill-cover-one-strike") {
            val caster = player("caster", at = Vec3(0.5, groundY, 0.0), team = "red", facing = Vec3(1.0, 0.0, 0.0))
            caster.world = world
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val spec = checkNotNull(sim.services.getOrThrow<ICustomBlockRegistry>().find(BlockType("astera:stone_bulwark")))
            val at = Vec3(2.0, groundY + 1, 0.0)
            assertThat(blocks.placeCustomBlock(world, at, spec, caster.id)).isInstanceOf(Result.Success::class.java)
            sim.world.placeBlock(world, at, BlockType(spec.id.key))

            // ADR-0297: floor(block_damage * matrix% / 100), both read from shipped content. A stone pickaxe is the case where
            // six floored portions (16/17 each, scaled) would have summed to less than the single strike.
            val content = Paths.get(System.getProperty("astera.sim.contentDir"))
            val yaml = Files.readString(content.resolve("skill-parts/pickaxe-bore.yaml"))
            val blockDamage = checkNotNull(Regex("""op: break_area[^\n]*?block_damage: (\d+)""").find(yaml)).groupValues[1].toInt()
            val percent = checkNotNull(spec.toolEffectiveness?.get(ToolType(ToolKind.PICKAXE, ToolTier.STONE)))
            val perCast = blockDamage * percent / 100
            assertThat(perCast).isLessThan(spec.baseDurability)

            sim.cast(caster, "pickaxe-bore-stone", "stone-pickaxe")
            val seen = linkedSetOf<Int?>()
            repeat(40) {
                advance(1)
                seen += hp(at)
            }

            // Only the untouched value and the one-strike value ever show: no intermediate portion states.
            assertThat(seen).containsExactlyInAnyOrder(spec.baseDurability, spec.baseDurability - perCast)
        }
    }

    @Test
    fun `cover damage expires through the same idle GC as terrain`() {
        scenario("skill-cover-no-regen") {
            val shooter = player("shooter", at = Vec3(0.5, groundY, 0.0), team = "red")
            shooter.world = world
            val wall = Vec3(0.0, groundY + 1, 6.0)
            cover("astera:reinforced_wall", wall, shooter = shooter)

            shooter.holds("pistol")
            shooter.rightClick()
            advance(20)
            assertThat(hp(wall)).isEqualTo(388)

            val recovery = BlockDamageGcUseCase(
                sim.services.getOrThrow<IBlockStateGateway>(),
                sim.scheduler,
            )
            assertThat(recovery.tick(world, currentTick = sim.scheduler.currentTick() + 221)).isEqualTo(1)
            assertThat(hp(wall)).isEqualTo(400)
            assertThat(sim.services.getOrThrow<IBlockStateGateway>().tracked(world)).isEmpty()
        }
    }

    @Test
    fun `a skill without block_damage leaves cover untouched`() {
        scenario("skill-cover-no-field") {
            val shooter = player("shooter", at = Vec3(0.5, groundY, 0.0), team = "red")
            shooter.world = world
            val wall = Vec3(0.0, groundY + 1, 6.0)
            cover("astera:reinforced_wall", wall, shooter = shooter)

            // simple-shoot is a projectile skill that never opted in.
            shooter.cast("simple-shoot")
            advance(40)

            assertThat(hp(wall)).isEqualTo(400)
        }
    }

    @Test
    fun `cover is rebuilt when the match ends and not before`() {
        scenario("skill-cover-rebuild", standaloneTeams = false) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val matchWorld = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId))
            val stageWorld = matchWorld.worldId
            red.world = stageWorld
            red.position = Vec3(0.5, groundY, 0.0)

            val wall = Vec3(0.0, groundY + 1, 3.0)
            val stone = Vec3(0.0, groundY + 1, 8.0)
            cover("astera:reinforced_wall", wall, stageWorld, red)
            cover("astera:reinforced_wall", stone, stageWorld, red)
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val hitter = sim.services.getOrThrow<HitCustomBlockUseCase>()

            // Damage one wall with a pickaxe swing (100 HP), break the other with four swings.
            advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
            hitter.hit(red.id, stageWorld, wall, ItemId("iron-pickaxe"))
            repeat(4) {
                advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
                hitter.hit(red.id, stageWorld, stone, ItemId("iron-pickaxe"))
            }
            assertThat(hp(wall, stageWorld)).isEqualTo(300)
            assertThat(hp(stone, stageWorld)).isZero()

            // Broken cover is not regenerated during the match: still damaged a moment later.
            advance(20)
            assertThat(hp(wall, stageWorld)).isLessThan(400)

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            advance(2)

            // The next match starts intact: full HP again, the broken wall back.
            assertThat(hp(wall, stageWorld)).isEqualTo(400)
            assertThat(hp(stone, stageWorld)).isEqualTo(400)
            assertThat(blocks.tracked(stageWorld).keys.filter { it.z == 2.0 }).isEmpty()
        }
    }
}
