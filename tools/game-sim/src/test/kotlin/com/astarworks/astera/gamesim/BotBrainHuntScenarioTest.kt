package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.BotBrain
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.atan2

/**
 * BotBrain's hunt wiring end to end through the real [com.astarworks.astera.gamesim.playable.SimBotHost] (ADR-0316): a bot
 * with a pistol on a stone slab with a wall in front of it, in a real annihilation match. A nearer enemy stands behind the
 * wall, a farther one in the open and walking sideways. The brain must (1) fight the visible enemy, never the walled-off
 * one, (2) fire with lead (yaw ahead of the enemy's current position, in its walking direction).
 */
class BotBrainHuntScenarioTest {

    private data class Shot(val yaw: Float, val botX: Double, val botZ: Double, val farX: Double, val farZ: Double)

    @Test
    fun `bot picks the visible enemy over a nearer walled-off one and fires with lead`() {
        val config = MatchConfig(
            teams = listOf("red", "blue"), teamSizes = mapOf("red" to 1, "blue" to 2), humanTeam = "blue", humanSeats = 2, seed = 7,
        )
        MatchDriver.boot(config).use { driver ->
            val sim = driver.sim
            val plan = driver.terrain.plan
            val world = driver.terrain.world
            val cx = (plan.minX + plan.maxX) / 2
            val cz = (plan.minZ + plan.maxZ) / 2
            val y = plan.surfaceY + 40.0

            // A slab high above the island with a 7x5 wall across z+3.
            for (x in cx - 60..cx + 60) for (z in cz - 60..cz + 60) {
                sim.world.placeBlock(world, Vec3(x.toDouble(), y - 1, z.toDouble()), BlockType("minecraft:stone"))
            }
            for (x in cx - 3..cx + 3) for (h in 0..4) {
                sim.world.placeBlock(world, Vec3(x.toDouble(), y + h, (cz + 3).toDouble()), BlockType("minecraft:stone"))
            }

            val bot = driver.seats.single { it.brain != null && it.team == "red" }
            val near = driver.humanSeats[0].entity
            val far = driver.humanSeats[1].entity
            val brain: BotBrain = bot.brain!!
            // The pool deals random weapons; this scenario is about the shooting brain, so the bot carries one pistol.
            sim.server.view(bot.entity.id)!!.givenItems.clear()
            sim.give(bot.entity, "pistol")
            sim.moveTo(bot.entity, Vec3(cx + 0.5, y, cz + 0.5))
            sim.moveTo(near, Vec3(cx + 0.5, y, cz + 6.5))
            var farX = cx + 20.5
            sim.moveTo(far, Vec3(farX, y, cz + 10.5))

            val shots = ArrayList<Shot>()
            var counted = 0
            driver.onTick {
                val n = brain.shots.get()
                if (n > counted) {
                    shots += Shot(driver.hostOf(bot).yawDeg, bot.entity.position.x, bot.entity.position.z, far.position.x, far.position.z)
                    counted = n
                }
                // the open enemy strafes sideways (+x) at 0.2 blocks/tick; the walled-off one stands still
                farX += 0.2
                sim.moveTo(far, Vec3(farX, y, cz + 10.5))
                sim.moveTo(near, Vec3(cx + 0.5, y, cz + 6.5))
                // the scenario is about aiming, so the bot stays on its spot instead of wandering round the wall
                sim.moveTo(bot.entity, Vec3(cx + 0.5, y, cz + 0.5))
            }
            driver.step(120)

            assertThat(driver.brainErrors).`as`("brains never throw").isEmpty()
            assertThat(brain.shots.get()).`as`("the bot shoots at the visible enemy").isGreaterThan(3)
            assertThat(shots).allSatisfy { s ->
                // Straight ahead (yaw 0) would be the walled-off enemy at (0, 6); the open one is to the side (yaw about -60 to -70).
                assertThat(s.yaw).`as`("aims at the enemy in the open, not at the nearer one behind the wall").isLessThan(-30f)
            }
            val late = shots.drop(2)
            assertThat(late).isNotEmpty
            assertThat(late).allSatisfy { s ->
                val direct = Math.toDegrees(atan2(-(s.farX - s.botX), s.farZ - s.botZ)).toFloat()
                assertThat(s.yaw).`as`("yaw leads the sideways-walking enemy (more negative than a direct aim)").isLessThan(direct - 0.3f)
            }
        }
    }
}
