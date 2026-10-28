package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.world.WorldRows
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.testfixtures.skillengine.FakeSkillWorldGateway
import org.assertj.core.api.Assertions.assertThat
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * W2-01 world ops (`break_area`, `place_shape`, `explode`, `lightning`): the op lowers to one `Action.World` row, and the
 * shared ledger gateway (production algorithm over an in-memory block map) spreads, orders and reverts the cells.
 */
class WorldOpScenarioTest : SkillEngineScenario() {
    private val caster: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val origin = Vector3d(0.5, 64.5, 0.5)
    private val east = Vector3d(1.0, 0.0, 0.0)

    private fun skill(opLine: String): String = """
        apiVersion: astera/v3
        kind: Skill
        id: world-probe
        anchors:
          - {id: body, anchor: caster}
        gameplay:
          timeline:
            - id: t0
              at: 0
              do:
                - $opLine
        """.trimIndent()

    private fun worldRow(opLine: String): Action.World {
        val r = loadInline(skill(opLine))
        assertThat(r).isInstanceOf(Result.Success::class.java)
        val rows = (r as Result.Success).value.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.World>()
        assertThat(rows).hasSize(1)
        return rows.single()
    }

    private fun run(world: FakeSkillWorldGateway, opLine: String) =
        WorldRows.execute(world, com.astarworks.astera.core.application.skillengine.runtime.port.CastRef(1L, caster, com.astarworks.astera.core.domain.world.WorldId("world")), worldRow(opLine), origin, east)

    private fun stoneBlock(world: FakeSkillWorldGateway, from: Int, to: Int) {
        for (x in from..to) for (y in 62..66) for (z in -4..4) world.setBlock(BlockCell(x, y, z), "minecraft:stone")
    }

    @Test
    fun worldRowSitsAtItsOwnTickNotDoubleShifted() {
        val src = skill("{id: b, op: break_area, anchor: body, shape: {sphere: {radius: 1.0, ahead: 0.0}}, tool: none, block_damage: 100, ripple: 0, revert: 200, outside_arena: false}")
            .replace("at: 0", "at: 7")
        val r = loadInline(src)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        val ticks = (r as Result.Success).value.table.states.flatMap { it.rows }.filter { it.action is Action.World }.map { it.tick }
        assertThat(ticks).containsExactly(7)
    }

    @Test
    fun breakAreaRippleOrderNearestFirst() {
        val world = FakeSkillWorldGateway()
        stoneBlock(world, -4, 4)
        run(world, "{id: b, op: break_area, anchor: body, shape: {sphere: {radius: 2.0, ahead: 0.0}}, tool: pickaxe, block_damage: 100, ripple: 29, revert: 200, outside_arena: false}")
        assertThat(world.strikes).isEmpty()
        repeat(40) { world.tick() }
        val centre = origin
        val distances = world.strikes.map { (_, cell) ->
            val dx = cell.x + 0.5 - centre.x
            val dy = cell.y + 0.5 - centre.y
            val dz = cell.z + 0.5 - centre.z
            dx * dx + dy * dy + dz * dz
        }
        assertThat(world.strikes).isNotEmpty()
        assertThat(distances).isSorted()
        assertThat(world.strikes.map { it.first }).isSorted()
        assertThat(world.strikes.first().first).isGreaterThanOrEqualTo(1)
        assertThat(world.strikes.last().first).isGreaterThan(world.strikes.first().first)
    }

    @Test
    fun breakAreaRippleZeroSameTick() {
        val world = FakeSkillWorldGateway()
        stoneBlock(world, -4, 4)
        run(world, "{id: b, op: break_area, anchor: body, shape: {sphere: {radius: 2.0, ahead: 0.0}}, tool: none, block_damage: 100, ripple: 0, revert: 200, outside_arena: false}")
        assertThat(world.strikes).isNotEmpty()
        assertThat(world.strikes.map { it.first }.toSet()).containsExactly(0)
    }

    @Test
    fun breakAreaRevertRestoresOriginal() {
        val world = FakeSkillWorldGateway()
        val cell = BlockCell(0, 64, 0)
        world.setBlock(cell, "minecraft:stone")
        run(world, "{id: b, op: break_area, anchor: body, shape: {sphere: {radius: 0.5, ahead: 0.0}}, tool: none, block_damage: 100, ripple: 0, revert: 5, outside_arena: false}")
        assertThat(world.block(cell)).isNull()
        repeat(4) { world.tick() }
        assertThat(world.block(cell)).isNull()
        world.tick()
        assertThat(world.block(cell)).isEqualTo("minecraft:stone")
    }

    @Test
    fun placeShapeWallAheadTwo() {
        val world = FakeSkillWorldGateway()
        run(world, "{id: w, op: place_shape, anchor: body, shape: {wall: {width: 3, height: 2}}, block: oak_planks, consume: none, ripple: 0, revert: 200, outside_arena: false}")
        val expected = (-1..1).flatMap { z -> (64..65).map { y -> BlockCell(2, y, z) } }
        assertThat(expected).allSatisfy { assertThat(world.block(it)).isEqualTo("minecraft:oak_planks") }
        assertThat(world.placements.map { it.second }).containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun placeShapeConsumePerCell() {
        val world = FakeSkillWorldGateway()
        world.giveItem("oak_planks", 4)
        run(world, "{id: w, op: place_shape, anchor: body, shape: {wall: {width: 3, height: 2}}, block: oak_planks, consume: oak_planks, ripple: 0, revert: 200, outside_arena: false}")
        assertThat(world.consumed).hasSize(4)
        assertThat(world.placements).hasSize(4)
    }

    @Test
    fun explodeCallsGatewayOnce() {
        val world = FakeSkillWorldGateway()
        run(world, "{id: e, op: explode, anchor: body, power: 2.0, break_blocks: false, fire: false}")
        assertThat(world.explosions).hasSize(1)
        val e = world.explosions.single()
        assertThat(e.caster).isEqualTo(caster)
        assertThat(e.power).isEqualTo(2.0)
        assertThat(e.breakBlocks).isFalse()
        assertThat(e.fire).isFalse()
    }

    @Test
    fun lightningVisualOnly() {
        val world = FakeSkillWorldGateway()
        run(world, "{id: l, op: lightning, anchor: body, visual_only: true}")
        assertThat(world.lightnings).hasSize(1)
        assertThat(world.lightnings.single().visualOnly).isTrue()
    }
}
