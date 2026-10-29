package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.world.WorldRows
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.skillengine.FakeSkillWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * World-op numbers (shape sizes, block_damage, ripple, revert) take `${param}` and a class-S `= CEL` like every other op, and the CEL
 * function `has_item(ref, 'item-id')` (ADR-0303 10.1b) reads the inventory in a `when:` guard.
 */
class WorldOpsAndHasItemScenarioTest : SkillEngineScenario() {
    private val caster: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
    private val origin = Vector3d(0.5, 64.5, 0.5)
    private val east = Vector3d(1.0, 0.0, 0.0)

    private fun load(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun worldRow(skill: CompiledSkill): Action.World =
        skill.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.World>().single()

    private fun specOf(skill: CompiledSkill): String = (worldRow(skill).payload["spec"] as Val.Known<*>).value.toString()

    @Test
    fun `world op numbers accept a param and a constant expression`() {
        val skill = load(
            """
            apiVersion: astera/v3
            kind: Skill
            id: world-numbers
            params:
              len: {type: number, default: 4}
              dmg: {type: number, default: 70}
            anchors:
              - {id: body, anchor: caster, mode: follow}
            machine:
              start: s
              states:
                s:
                  for: 20
                  do:
                    - {id: b, op: break_area, anchor: body, shape: {line: {length: "${'$'}{len}", width: "= 1 + 2", height: 2}}, tool: pickaxe, block_damage: "${'$'}{dmg}", ripple: "= 2 * 3", revert: "= 100 + 100", outside_arena: false}
            """.trimIndent(),
        )
        assertThat(specOf(skill)).contains("length=4.0").contains("width=3.0").contains("blockDamage=70").contains("rippleTicks=6").contains("revertTicks=200")
    }

    @Test
    fun `place_shape sizes are folded and still range checked`() {
        val ok = load(
            """
            apiVersion: astera/v3
            kind: Skill
            id: build-numbers
            params:
              h: {type: number, default: 3}
            anchors:
              - {id: body, anchor: caster, mode: follow}
            machine:
              start: s
              states:
                s:
                  for: 20
                  do:
                    - {id: p, op: place_shape, anchor: body, shape: {pillar: {height: "${'$'}{h}"}}, block: minecraft:stone, consume: none, ripple: 0, revert: "= 40", outside_arena: false}
            """.trimIndent(),
        )
        assertThat(specOf(ok)).contains("height=3")
        val bad = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: build-bad
            anchors:
              - {id: body, anchor: caster, mode: follow}
            machine:
              start: s
              states:
                s:
                  for: 20
                  do:
                    - {id: p, op: place_shape, anchor: body, shape: {pillar: {height: "= 60 + 10"}}, block: minecraft:stone, consume: none, ripple: 0, revert: 40, outside_arena: false}
            """.trimIndent(),
        )
        assertThat(bad).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `a folded break_area strikes with the folded numbers`() {
        val skill = load(
            """
            apiVersion: astera/v3
            kind: Skill
            id: world-strike
            params:
              r: {type: number, default: 0.5}
            anchors:
              - {id: body, anchor: caster, mode: follow}
            machine:
              start: s
              states:
                s:
                  for: 20
                  do:
                    - {id: b, op: break_area, anchor: body, shape: {sphere: {radius: "${'$'}{r}", ahead: 0.0}}, tool: shovel, block_damage: "= 50 * 2", ripple: 0, revert: 4000, outside_arena: false}
            """.trimIndent(),
        )
        val world = FakeSkillWorldGateway()
        world.setBlock(BlockCell(0, 64, 0), "minecraft:dirt")
        WorldRows.execute(world, CastRef(1L, caster, WorldId("world"), "any"), worldRow(skill), origin, east) { v -> ((v as Val.Known<*>).value as Number).toDouble() }
        assertThat(world.block(BlockCell(0, 64, 0))).isNull()
    }

    private fun hasItemSkill(): CompiledSkill = load(
        """
        apiVersion: astera/v3
        kind: Skill
        id: has-item-guard
        item: any
        machine:
          start: s
          states:
            s:
              for: 10
              do:
                - {id: take, op: consume_item, item: arrow, count: 1, when: "= has_item(caster, 'arrow')"}
        """.trimIndent(),
    )

    @Test
    fun `has_item gates a row on the inventory`() {
        val held = GatewayRecorder().also { it.stock["arrow"] = 3 }
        val withItem = play(hasItemSkill(), 1L, emptyList(), null, held)
        assertThat(withItem.filter { it.verb == "item" && it.payload.contains("consume=1") }).hasSize(1)
        assertThat(held.stock["arrow"]).isEqualTo(2)

        val empty = GatewayRecorder()
        val without = play(hasItemSkill(), 1L, emptyList(), null, empty)
        assertThat(without.filter { it.verb == "item" && it.payload.contains("consume=1") }).isEmpty()
    }

    @Test
    fun `has_item with a non-reference argument fails to load`() {
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: has-item-bad
            item: any
            machine:
              start: s
              states:
                s:
                  for: 10
                  do:
                    - {id: take, op: consume_item, item: arrow, count: 1, when: "= has_item(3, 'arrow')"}
            """.trimIndent(),
        )
        assertThat(r).isInstanceOf(Result.Failure::class.java)
    }
}
