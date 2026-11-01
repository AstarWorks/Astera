package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.domain.event.PlayerRespawned
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.floor

/**
 * No seat stands inside a solid block after a spawn / respawn (feet and head cells free), and a jump under a low ceiling is
 * cut short instead of lifting the head into the block (SimLocomotion.lift). Also asserted: a block that a skill's
 * SkillWorldEditor.restore would put back into a cell where a bot has meanwhile walked is deferred until the bot
 * leaves (the match's `-36,61,4` buried bots came from there), so no seat ever stands buried without a jump in progress.
 */
class BotBuriedSeatScenarioTest {

    @Test
    fun `no seat spawns or respawns inside a solid block and jumps stop at the ceiling`() {
        MatchDriver.boot(MatchConfig(mode = "annihilation", stage = "annihilation-arena", teams = listOf("red", "blue", "yellow"), botsPerTeam = 3, seed = 20261101L)).use { driver ->
            val terrain = driver.terrain
            fun buried(x: Double, y: Double, z: Double): Boolean {
                val bx = floor(x).toInt()
                val bz = floor(z).toInt()
                val by = floor(y + 0.05).toInt()
                return terrain.isOccupied(bx, by, bz) || terrain.isOccupied(bx, by + 1, bz)
            }
            val spawnBuried = ArrayList<String>()
            val jumpBuried = ArrayList<String>()
            val restoreBuried = HashSet<String>()
            var seen = 0
            driver.onTick { t ->
                val respawns = driver.sim.eventsOf<PlayerRespawned>()
                for (e in respawns.drop(seen)) {
                    val p = e.spawnPoint.position
                    if (buried(p.x, p.y, p.z)) spawnBuried += "${driver.sim.world.find(e.playerId)?.name}@$t respawn at ${p.x},${p.y},${p.z}"
                }
                seen = respawns.size
                for (s in driver.seats) {
                    val p = s.entity.position
                    if (!buried(p.x, p.y, p.z)) continue
                    val loco = driver.hostOf(s).locomotion
                    if (loco.lift > 0.0) jumpBuried += "${s.name}@$t at $p lift=${loco.lift}" else restoreBuried += s.name
                }
            }
            for (s in driver.seats) {
                val p = s.entity.position
                if (buried(p.x, p.y, p.z)) spawnBuried += "${s.name} initial at ${p.x},${p.y},${p.z}"
            }
            driver.stepUntil(12_000) { driver.ended }
            println("[buried] spawn=${spawnBuried.size} jump=${jumpBuried.size} walked-in-block-restored seats=$restoreBuried")
            assertThat(spawnBuried).`as`("seats spawned inside solid blocks").isEmpty()
            assertThat(jumpBuried.take(5)).`as`("jumps lifted the head into a block").isEmpty()
            assertThat(restoreBuried).`as`("seats buried by a restored skill block").isEmpty()
        }
    }
}
