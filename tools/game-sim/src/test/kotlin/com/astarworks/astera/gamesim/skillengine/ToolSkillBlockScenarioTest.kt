package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.world.WorldRows
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.skillengine.FakeSkillWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tool skills break blocks by the (tool class x item tier x block) rule, not by a flat 100: the production `break_area` ledger runs over a
 * block map that applies `BlockHpRules` exactly like the Paper gateway, with the tier the cast's item names. Counts are casts until the
 * block is gone (the cell is struck once per cast at `block_damage: 100`).
 */
class ToolSkillBlockScenarioTest : SkillEngineScenario() {
    private val caster: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    private val origin = Vector3d(0.5, 64.5, 0.5)
    private val east = Vector3d(1.0, 0.0, 0.0)
    private val cell = BlockCell(0, 64, 0)

    private fun tierOfItem(item: String?): ToolTier = when {
        item == null -> ToolTier.NONE
        item.startsWith("stone-") -> ToolTier.STONE
        item.startsWith("iron-") -> ToolTier.IRON
        item.startsWith("diamond-") -> ToolTier.DIAMOND
        else -> ToolTier.NONE
    }

    private fun worldRow(opLine: String): Action.World {
        val src = """
            apiVersion: astera/v3
            kind: Skill
            id: tool-probe
            anchors:
              - {id: body, anchor: caster, mode: follow}
            machine:
              start: s
              states:
                s:
                  for: 20
                  do:
                    - $opLine
        """.trimIndent()
        val r = loadInline(src)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        return (r as Result.Success).value.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.World>().single()
    }

    private fun cast(world: FakeSkillWorldGateway, item: String, opLine: String, castId: Long) =
        WorldRows.execute(world, CastRef(castId, caster, WorldId("world"), item), worldRow(opLine), origin, east) { v -> ((v as Val.Known<*>).value as Number).toDouble() }

    private fun strike(toolClass: String) =
        "{id: b, op: break_area, anchor: body, shape: {sphere: {radius: 0.5}}, tool: $toolClass, block_damage: 100, ripple: 0, revert: 4000, outside_arena: false}"

    /** Casts until [block] is broken by [item]'s [toolClass] skill; -1 when it survives [limit] casts. */
    private fun castsToBreak(block: String, toolClass: String, item: String, limit: Int = 30): Int {
        val world = FakeSkillWorldGateway(tierOf = ::tierOfItem)
        world.setBlock(cell, block)
        for (n in 1..limit) {
            cast(world, item, strike(toolClass), n.toLong())
            if (world.block(cell) == null) return n
        }
        return -1
    }

    @Test
    fun `casts to break follow the item tier and the block`() {
        // stone: HP 150
        assertThat(castsToBreak("minecraft:stone", "pickaxe", "stone-pickaxe")).isEqualTo(2)
        assertThat(castsToBreak("minecraft:stone", "pickaxe", "iron-pickaxe")).isEqualTo(1)
        assertThat(castsToBreak("minecraft:stone", "pickaxe", "diamond-pickaxe")).isEqualTo(1)
        // cobblestone: HP 200, only a diamond pickaxe finishes it in one cast
        assertThat(castsToBreak("minecraft:cobblestone", "pickaxe", "stone-pickaxe")).isEqualTo(2)
        assertThat(castsToBreak("minecraft:cobblestone", "pickaxe", "iron-pickaxe")).isEqualTo(2)
        assertThat(castsToBreak("minecraft:cobblestone", "pickaxe", "diamond-pickaxe")).isEqualTo(1)
        // planks: HP 200, the axe is the preferred class
        assertThat(castsToBreak("minecraft:oak_planks", "axe", "stone-axe")).isEqualTo(2)
        assertThat(castsToBreak("minecraft:oak_planks", "axe", "iron-axe")).isEqualTo(2)
        assertThat(castsToBreak("minecraft:oak_planks", "axe", "diamond-axe")).isEqualTo(1)
        // dirt: HP 50, soft, one cast for any shovel
        assertThat(castsToBreak("minecraft:dirt", "shovel", "stone-shovel")).isEqualTo(1)
    }

    @Test
    fun `the wrong tool class digs at a tenth`() {
        // an axe on stone (HP 150): 10 per stone-tier cast, 20 per diamond cast
        assertThat(castsToBreak("minecraft:stone", "axe", "stone-axe")).isEqualTo(15)
        assertThat(castsToBreak("minecraft:stone", "axe", "diamond-axe")).isEqualTo(8)
    }

    @Test
    fun `a line with height fells a column of that height`() {
        val world = FakeSkillWorldGateway(tierOf = ::tierOfItem)
        for (x in 0..4) for (y in 60..68) for (z in -2..2) world.setBlock(BlockCell(x, y, z), "minecraft:oak_log")
        cast(world, "iron-axe", "{id: b, op: break_area, anchor: body, shape: {line: {length: 3, width: 1, height: 3}}, tool: axe, block_damage: 100, ripple: 0, revert: 4000, outside_arena: false}", 1L)
        assertThat(world.strikes.map { it.second.y }.toSet()).containsExactlyInAnyOrder(63, 64, 65)
        assertThat(world.strikes.map { it.second.z }.toSet()).containsExactly(0)
    }

    @Test
    fun `break_area never touches an excluded ore node cell`() {
        val node = BlockCell(0, 64, 0)
        val world = FakeSkillWorldGateway(tierOf = ::tierOfItem, excluded = { it == node })
        world.setBlock(node, "minecraft:raw_iron_block")
        world.setBlock(BlockCell(1, 64, 0), "minecraft:stone")
        cast(world, "diamond-pickaxe", "{id: b, op: break_area, anchor: body, shape: {sphere: {radius: 2.0}}, tool: pickaxe, block_damage: 100, ripple: 0, revert: 4000, outside_arena: true}", 1L)
        assertThat(world.block(node)).isEqualTo("minecraft:raw_iron_block")
        assertThat(world.strikes.map { it.second }).doesNotContain(node)
        assertThat(world.block(BlockCell(1, 64, 0))).isNull()
    }
}
