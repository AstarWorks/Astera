package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.OreNodeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Match arsenal: Annihilation-style Mid ore nodes (content/ore-nodes/siege-isles.yaml) are mined by tier and regrow. */
class MidOreNodeScenarioTest {

    @Test
    fun `a node gives its ore to a pickaxe of the right tier, a weaker pickaxe is refused, and it regrows`() {
        scenario("mid-ore-node") {
            val red = player("red-miner", team = "red")
            val blue = player("blue-miner", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val inventory = checkNotNull(sim.server.view(red.id)).givenItems

            // Iron node (stone tier, regrow 20 s) at stage-relative (5, 65, 5); gold node (iron tier, regrow 45 s) at (0, 65, 12).
            val ironCell = Vec3(origin.x + 5, 65.0, origin.z + 5)
            val goldCell = Vec3(origin.x + 0, 65.0, origin.z + 12)
            fun material(cell: Vec3) = sim.oreNodes.materialAt(cell.x.toInt(), cell.y.toInt(), cell.z.toInt())
            val ironOre = checkNotNull(material(ironCell)) { "the iron node was never placed" }

            // Bare hand and a wood-class tool do nothing.
            assertThat(nodes.mine(red.id, worldId, ironCell, null)).isEqualTo(OreNodeService.Outcome.TooWeak)
            assertThat(material(ironCell)).isEqualTo(ironOre)

            // The node has HP (200): a stone pickaxe (100 a hit) only cracks it on the first swing and a swing inside the hit cooldown is ignored.
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Hit(100, 200))
            assertThat(sim.oreNodes.crackAt(ironCell.x.toInt(), 65, ironCell.z.toInt())).isEqualTo(100 to 200)
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Cooldown)
            assertThat(inventory).doesNotContain("ore-iron")
            assertThat(material(ironCell)).isEqualTo(ironOre)

            // The second swing finishes it: ore arrives, the block becomes the placeholder, the crack is gone.
            advance(6)
            val mined = nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))
            assertThat(sim.oreNodes.crackAt(ironCell.x.toInt(), 65, ironCell.z.toInt())).isNull()
            assertThat(mined).isInstanceOf(OreNodeService.Outcome.Mined::class.java)
            assertThat(inventory).contains("ore-iron")
            assertThat(material(ironCell)).isNotEqualTo(ironOre)

            // While regrowing it cannot be mined again.
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Regrowing)

            // After regrow_seconds the ore is back.
            advance(20 * 20 + 5)
            assertThat(material(ironCell)).isEqualTo(ironOre)

            // The gold node needs an iron pickaxe: the stone one is refused and gives nothing.
            val goldOre = checkNotNull(material(goldCell))
            assertThat(nodes.mine(red.id, worldId, goldCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.TooWeak)
            assertThat(inventory).doesNotContain("ore-gold")
            assertThat(material(goldCell)).isEqualTo(goldOre)
            // Gold has 300 HP; an iron pickaxe deals 100 a hit (3 swings), a diamond one 200 (2 swings).
            advance(6)
            assertThat(nodes.mine(red.id, worldId, goldCell, ItemId("iron-pickaxe"))).isEqualTo(OreNodeService.Outcome.Hit(200, 300))
            advance(6)
            assertThat(nodes.mine(red.id, worldId, goldCell, ItemId("iron-pickaxe"))).isEqualTo(OreNodeService.Outcome.Hit(100, 300))
            assertThat(inventory).doesNotContain("ore-gold")
            advance(6)
            assertThat(nodes.mine(red.id, worldId, goldCell, ItemId("iron-pickaxe"))).isInstanceOf(OreNodeService.Outcome.Mined::class.java)
            assertThat(inventory).contains("ore-gold")

            // Diamond pickaxe on the regrown iron node: 250 a hit, one swing.
            advance(60 * 20)
            advance(6)
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("diamond-pickaxe"))).isInstanceOf(OreNodeService.Outcome.Mined::class.java)

            // Not a node: an ordinary cell is left to the caller.
            assertThat(nodes.mine(red.id, worldId, Vec3(origin.x + 1, 65.0, origin.z + 1), ItemId("iron-pickaxe"))).isEqualTo(OreNodeService.Outcome.NotANode)

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
        }
    }
}
