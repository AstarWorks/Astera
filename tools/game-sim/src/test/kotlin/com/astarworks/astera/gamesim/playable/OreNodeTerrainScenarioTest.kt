package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every ore node cell the shipped content defines (base forge banks and Mid alike) is a block of the sim terrain, as on Paper where
 * `PaperOreNodeWorld` sets it at match start, and a base node is dug through the real path: SimBotHost.dig -> OreDigHold -> OreNodeService.
 */
class OreNodeTerrainScenarioTest {

    @Test
    fun `every shipped ore node cell is occupied in the sim terrain and a base iron node can be dug by a held tool`() {
        MatchDriver.boot(
            MatchConfig(
                stage = "annihilation-arena", teams = listOf("red", "blue"), teamSizes = mapOf("red" to 1, "blue" to 1),
                humanTeam = "red", humanSeats = 1, seed = 21,
            ),
        ).use { driver ->
            driver.step(60)
            val origin = driver.terrain.stage.origin
            val config = checkNotNull(driver.sim.services.getOrThrow<IOreNodeRegistry>().find("annihilation-arena"))
            val cells = config.nodes.flatMap { g ->
                g.positions.map { Triple(it.x.toInt(), it.y.toInt(), it.z.toInt()) } +
                    (
                        g.region?.let { r ->
                            (r.min.x.toInt()..r.max.x.toInt()).flatMap { x ->
                                (r.min.y.toInt()..r.max.y.toInt()).flatMap { y -> (r.min.z.toInt()..r.max.z.toInt()).map { z -> Triple(x, y, z) } }
                            }
                        } ?: emptyList()
                    )
            }
            assertThat(cells).isNotEmpty()
            val missing = cells.filter { (x, y, z) -> !driver.terrain.isOccupied(x + origin.x.toInt(), y, z + origin.z.toInt()) }
            assertThat(missing).`as`("ore node cells absent from the sim terrain").isEmpty()
            assertThat(cells.any { it.second == 65 }).`as`("base forge-bank nodes are among them").isTrue()

            val seat = driver.humanSeats.single()
            val host = driver.hostOf(seat)
            driver.sim.give(seat.entity, "stone-pickaxe")
            driver.step(2)
            val slot = host.hotbar().indexOf("stone-pickaxe".hashCode() and 0x7fffffff)
            assertThat(slot).`as`("the pickaxe is in the hotbar").isGreaterThanOrEqualTo(0)
            host.selectHotbar(slot)
            val bx = origin.x.toInt() - 31
            val bz = origin.z.toInt() - 92
            seat.entity.position = Vec3(bx + 1.5, 65.0, bz + 0.5)
            host.dig(bx, 65, bz, 3000)
            driver.step(2)
            assertThat(driver.sim.oreNodes.crackAt(bx, 65, bz)).`as`("the first hit cracked the base node").isNotNull()
            assertThat(driver.terrain.isOccupied(bx, 65, bz)).`as`("a dig on a node is cancelled like on Paper, the block is not removed").isTrue()
            driver.step(60)
            assertThat(driver.sim.oreNodes.mined.any { it.x == bx && it.y == 65 && it.z == bz }).`as`("the held dig finished the node").isTrue()
        }
    }
}
