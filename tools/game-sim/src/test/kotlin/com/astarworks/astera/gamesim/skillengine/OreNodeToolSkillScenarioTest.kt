package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.world.WorldRows
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.gamesim.scenario
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.testfixtures.skillengine.FakeSkillWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A tool skill (`break_area`) strikes a Mid ore node under the same rules as a hand break: tool-class affinity, the Norma level gate,
 * node HP; the ore drops to the caster and the node regrows. The skill never reports the node broken (the ledger would restore the ore).
 */
class OreNodeToolSkillScenarioTest : SkillEngineScenario() {

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
            id: ore-probe
            anchors:
              - {id: body, anchor: caster}
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

    private fun strike(toolClass: String) =
        "{id: b, op: break_area, anchor: body, shape: {sphere: {radius: 0.5, ahead: 0.0}}, tool: $toolClass, block_damage: 100, ripple: 0, revert: 4000, outside_arena: false}"

    @Test
    fun `break_area chips an ore node by tool affinity, finishes it into drops and regrowth, and respects the Norma level gate`() {
        scenario("ore-node-tool-skill") {
            val red = player("red-miner", team = "red")
            val blue = player("blue-miner", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val inventory = checkNotNull(sim.server.view(red.id)).givenItems
            advance(25)

            val world = FakeSkillWorldGateway(tierOf = ::tierOfItem, oreNodes = nodes, oreWorld = worldId)
            fun cast(item: String, toolClass: String, cell: Vec3, castId: Long) {
                val c = BlockCell(cell.x.toInt(), cell.y.toInt(), cell.z.toInt())
                WorldRows.execute(
                    world, CastRef(castId, red.id.uuid, worldId, item), worldRow(strike(toolClass)),
                    Vector3d(c.x + 0.5, c.y + 0.5, c.z + 0.5), Vector3d(1.0, 0.0, 0.0),
                ) { v -> ((v as Val.Known<*>).value as Number).toDouble() }
            }
            // The fake block map mirrors the world: the node cells hold an ore block the skill sees as non-air.
            for ((x, z) in listOf(20 to 0, 4 to 0)) world.setBlock(BlockCell((origin.x + x).toInt(), 65, (origin.z + z).toInt()), "minecraft:stone")
            fun material(cell: Vec3) =sim.oreNodes.materialAt(cell.x.toInt(), cell.y.toInt(), cell.z.toInt())

            // Iron node (800 HP, pickaxe): a stone pickaxe skill hit deals 100 x skill_mult 3.0 = 300; a stone axe only 15 x 3 = 45.
            val iron = Vec3(origin.x + 20, 65.0, origin.z)
            val ironOre = checkNotNull(material(iron))
            cast("stone-axe", "axe", iron, 1L)
            assertThat(sim.oreNodes.crackAt(iron.x.toInt(), 65, iron.z.toInt())).isEqualTo(755 to 800)
            cast("stone-pickaxe", "pickaxe", iron, 2L)
            assertThat(sim.oreNodes.crackAt(iron.x.toInt(), 65, iron.z.toInt())).isEqualTo(455 to 800)
            cast("stone-pickaxe", "pickaxe", iron, 3L)
            assertThat(inventory).doesNotContain("ore-iron")
            cast("stone-pickaxe", "pickaxe", iron, 4L)
            // 455 - 300 - 300 = -145: broken -> drops arrive, the block is the placeholder, and the cast never reported BROKEN (no revert).
            assertThat(inventory).contains("ore-iron")
            assertThat(material(iron)).isNotEqualTo(ironOre)
            advance(20 * 20 + 5)
            assertThat(material(iron)).isEqualTo(ironOre)

            // Emerald needs Norma level 4: a diamond pickaxe skill at level 1 does nothing to it.
            val emerald = Vec3(origin.x + 4, 65.0, origin.z)
            cast("diamond-pickaxe", "pickaxe", emerald, 5L)
            assertThat(sim.oreNodes.crackAt(emerald.x.toInt(), 65, emerald.z.toInt())).isNull()
            assertThat(sim.oreNodes.denied).contains(Triple(emerald.x.toInt(), 65, emerald.z.toInt()))

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
        }
    }
}
