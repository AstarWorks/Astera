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
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.usecase.block.BlockStateAutoRecoveryUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0280: cover damage from skills. A gun/spell chips cover only through its `block_damage` field, the field
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
        sim.services.getOrThrow<IBlockStateGateway>().tracked(inWorld)[cell]?.durability

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
    fun `damaged cover does not regenerate during the match`() {
        scenario("skill-cover-no-regen") {
            val shooter = player("shooter", at = Vec3(0.5, groundY, 0.0), team = "red")
            shooter.world = world
            val wall = Vec3(0.0, groundY + 1, 6.0)
            cover("astera:reinforced_wall", wall, shooter = shooter)

            shooter.holds("pistol")
            shooter.rightClick()
            advance(20)
            assertThat(hp(wall)).isEqualTo(388)

            val recovery = BlockStateAutoRecoveryUseCase(
                sim.services.getOrThrow<IBlockStateGateway>(),
                sim.scheduler,
                coolOffTicks = 0L,
            )
            assertThat(recovery.tick(world, currentTick = 100_000L)).isEqualTo(0)
            assertThat(hp(wall)).isEqualTo(388)
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
    fun `the pickaxe breach strike smashes a radius of stone and falls in two strikes`() {
        scenario("skill-cover-breach") {
            val miner = player("miner", at = Vec3(0.5, groundY, 0.0), team = "red")
            miner.world = world
            val a = Vec3(0.0, groundY + 1, 3.0)
            val b = Vec3(1.0, groundY + 1, 3.0)
            val c = Vec3(0.0, groundY + 1, 4.0)
            for (cell in listOf(a, b, c)) cover("astera:reinforced_wall", cell, shooter = miner)

            // 300 HP of pickaxe damage on every cover cell within radius 2 of where the look ray meets cover.
            miner.cast("field-pickaxe-breach")
            assertThat(listOf(hp(a), hp(b), hp(c))).containsExactly(100, 100, 100)

            // The skill has an 8 s cooldown; after it the second strike finishes them.
            advance(170)
            miner.cast("field-pickaxe-breach")
            assertThat(listOf(hp(a), hp(b), hp(c))).containsExactly(0, 0, 0)
        }
    }

    @Test
    fun `the axe cleave cuts a line of barricades in one swing`() {
        scenario("skill-cover-cleave") {
            val cutter = player("cutter", at = Vec3(0.5, groundY, 0.0), team = "red")
            cutter.world = world
            val near = Vec3(0.0, groundY + 1, 2.0)
            val far = Vec3(0.0, groundY + 1, 5.0)
            val aside = Vec3(4.0, groundY + 1, 3.0)
            cover("astera:barricade", near, shooter = cutter)
            cover("astera:barricade", far, shooter = cutter)
            cover("astera:barricade", aside, shooter = cutter)

            cutter.cast("field-axe-cleave")

            // 300 HP of axe damage on wood: the barricades on the line fall, the one off the line stands.
            assertThat(hp(near)).isZero()
            assertThat(hp(far)).isZero()
            assertThat(hp(aside)).isEqualTo(300)
        }
    }

    @Test
    fun `the shovel dig-in places a sandbag wall that the tools can break`() {
        scenario("skill-cover-dig-in") {
            val digger = player("digger", at = Vec3(0.5, groundY, 0.0), team = "red")
            digger.world = world
            val blocks = sim.services.getOrThrow<IBlockStateGateway>()
            val hitter = sim.services.getOrThrow<HitCustomBlockUseCase>()

            digger.cast("field-shovel-dig-in")

            // 3 wide x 2 tall, two blocks ahead (+z), all sandbags (200 HP).
            val cells = listOf(-1.0, 0.0, 1.0).flatMap { x -> listOf(0.0, 1.0).map { h -> Vec3(x, groundY + h, 2.0) } }
            assertThat(cells.map { hp(it) }).containsOnly(200)
            assertThat(blocks.isCover(world, cells.first())).isTrue()

            // They are ordinary cover: the shovel breaks one in two swings.
            val target = cells[2]
            repeat(2) {
                advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
                hitter.hit(digger.id, world, target, ItemId("field-shovel"))
            }
            assertThat(hp(target)).isZero()
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

            // Damage one wall by 300 (breach), break the other with the pickaxe, and dig in a sandbag wall.
            red.cast("field-pickaxe-breach")
            repeat(4) {
                advance(HitCustomBlockUseCase.HIT_COOLDOWN_TICKS.toInt() + 1)
                hitter.hit(red.id, stageWorld, stone, ItemId("field-pickaxe"))
            }
            red.cast("field-shovel-dig-in")
            assertThat(hp(wall, stageWorld)).isEqualTo(100)
            assertThat(hp(stone, stageWorld)).isZero()
            val dugIn = blocks.tracked(stageWorld).keys.filter { it.z == 2.0 }
            assertThat(dugIn).hasSize(6)

            // Broken cover is not regenerated during the match: still damaged a moment later.
            advance(20)
            assertThat(hp(wall, stageWorld)).isLessThan(400)

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            advance(2)

            // The next match starts intact: full HP again, the dug-in wall gone.
            assertThat(hp(wall, stageWorld)).isEqualTo(400)
            assertThat(hp(stone, stageWorld)).isEqualTo(400)
            assertThat(blocks.tracked(stageWorld).keys.filter { it.z == 2.0 }).isEmpty()
        }
    }
}
