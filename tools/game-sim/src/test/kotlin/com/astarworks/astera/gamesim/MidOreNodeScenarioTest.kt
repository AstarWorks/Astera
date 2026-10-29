package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Match arsenal: Annihilation-style Mid ore nodes (content/ore-nodes/siege-isles.yaml) have HP, a tool affinity and a Norma level gate.
 * Stage-relative cells (y=65): iron (20,0), gold (8,8), emerald (4,0), ironwood/timber (-16,-4) and (-16,4), iron clay (-20,-4).
 */
class MidOreNodeScenarioTest {

    @Test
    fun `a node gives its ore to a tool of the right tier, a weaker tool is refused, and it regrows`() {
        scenario("mid-ore-node") {
            val red = player("red-miner", team = "red")
            val blue = player("blue-miner", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val inventory = checkNotNull(sim.server.view(red.id)).givenItems

            val ironCell = Vec3(origin.x + 20, 65.0, origin.z)
            fun material(cell: Vec3) = sim.oreNodes.materialAt(cell.x.toInt(), cell.y.toInt(), cell.z.toInt())
            val ironOre = checkNotNull(material(ironCell)) { "the iron node was never placed" }

            // Bare hand does nothing.
            assertThat(nodes.mine(red.id, worldId, ironCell, null)).isEqualTo(OreNodeService.Outcome.TooWeak)
            assertThat(material(ironCell)).isEqualTo(ironOre)

            // The node has HP (800): a stone pickaxe (100 a hit) cracks it and a swing inside the hit cooldown is ignored.
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Hit(700, 800))
            assertThat(sim.oreNodes.crackAt(ironCell.x.toInt(), 65, ironCell.z.toInt())).isEqualTo(700 to 800)
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Cooldown)
            assertThat(inventory).doesNotContain("ore-iron")

            // Seven more swings finish it: ore arrives, the block becomes the placeholder, the crack is gone.
            repeat(6) {
                advance(6)
                assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isInstanceOf(OreNodeService.Outcome.Hit::class.java)
            }
            advance(6)
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isInstanceOf(OreNodeService.Outcome.Mined::class.java)
            assertThat(sim.oreNodes.crackAt(ironCell.x.toInt(), 65, ironCell.z.toInt())).isNull()
            assertThat(inventory).contains("ore-iron")
            assertThat(material(ironCell)).isNotEqualTo(ironOre)

            // While regrowing it cannot be mined again; after regrow_seconds the ore is back.
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Regrowing)
            advance(20 * 20 + 5)
            assertThat(material(ironCell)).isEqualTo(ironOre)

            // Diamond pickaxe on the regrown node: 250 a hit, 4 swings.
            repeat(3) {
                advance(6)
                assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("diamond-pickaxe"))).isInstanceOf(OreNodeService.Outcome.Hit::class.java)
            }
            advance(6)
            assertThat(nodes.mine(red.id, worldId, ironCell, ItemId("diamond-pickaxe"))).isInstanceOf(OreNodeService.Outcome.Mined::class.java)

            // Not a node: an ordinary cell is left to the caller.
            assertThat(nodes.mine(red.id, worldId, Vec3(origin.x + 1, 65.0, origin.z + 1), ItemId("iron-pickaxe"))).isEqualTo(OreNodeService.Outcome.NotANode)

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
        }
    }

    @Test
    fun `the matching tool breaks a node fast and the other classes only scratch it`() {
        scenario("mid-ore-affinity") {
            val red = player("red-miner", team = "red")
            val blue = player("blue-miner", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val nodes = sim.services.getOrThrow<OreNodeService>()
            fun cell(x: Int, z: Int) = Vec3(origin.x + x, 65.0, origin.z + z)

            // Ironwood (axe affinity, 800 HP): a stone axe deals 100, a stone pickaxe only 12.
            val timberAxe = cell(-16, -4)
            val timberPick = cell(-16, 4)
            assertThat(nodes.mine(red.id, worldId, timberAxe, ItemId("stone-axe"))).isEqualTo(OreNodeService.Outcome.Hit(700, 800))
            assertThat(nodes.mine(red.id, worldId, timberPick, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Hit(788, 800))

            // Iron clay (shovel affinity, 800 HP): an iron shovel deals 150.
            advance(6)
            assertThat(nodes.mine(red.id, worldId, cell(-20, -4), ItemId("iron-shovel"))).isEqualTo(OreNodeService.Outcome.Hit(650, 800))

            // An iron node is the pickaxe's: a stone axe scratches 15 off it, a stone pickaxe 100.
            advance(6)
            assertThat(nodes.mine(red.id, worldId, cell(20, 0), ItemId("stone-axe"))).isEqualTo(OreNodeService.Outcome.Hit(785, 800))
            advance(6)
            assertThat(nodes.mine(red.id, worldId, cell(20, 0), ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.Hit(685, 800))

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
        }
    }

    @Test
    fun `a node below the player's Norma level takes no damage, refuses with a sound and action bar, and opens when the level is reached`() {
        scenario("mid-ore-level-gate") {
            val red = player("red-miner", team = "red")
            val blue = player("blue-miner", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("siege-isles"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val bars = checkNotNull(sim.server.view(red.id)).actionBars
            val gold = Vec3(origin.x + 8, 65.0, origin.z + 8)
            val emerald = Vec3(origin.x + 4, 65.0, origin.z)
            advance(25)

            // Level 1: gold needs Norma level 2, emerald level 4. The swing does nothing, plays the deny sound and shows the level.
            assertThat(nodes.mine(red.id, worldId, gold, ItemId("diamond-pickaxe"))).isEqualTo(OreNodeService.Outcome.LevelLocked(2))
            assertThat(bars.map { it.value }).contains("astera.ore.needs-level")
            assertThat(sim.oreNodes.denied).contains(Triple(gold.x.toInt(), 65, gold.z.toInt()))
            assertThat(sim.oreNodes.crackAt(gold.x.toInt(), 65, gold.z.toInt())).isNull()

            // Ten stars clear level 1 (STAR goal): gold opens, with the level-2 speed multiplier (1.15 x 120 = 138 per iron-pickaxe hit).
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            stars.add(matchId, red.id, 10)
            bus.publish(StarsCollected(matchId, red.id, 1, stars.get(matchId, red.id)))
            advance(5)
            assertThat(nodes.mine(red.id, worldId, gold, ItemId("iron-pickaxe"))).isEqualTo(OreNodeService.Outcome.Hit(1062, 1200))

            // Emerald (level 4) is still locked at level 2.
            advance(6)
            assertThat(nodes.mine(red.id, worldId, emerald, ItemId("diamond-pickaxe"))).isEqualTo(OreNodeService.Outcome.LevelLocked(4))

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
        }
    }
}
