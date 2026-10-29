package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F3-5: death -> spectator state (death cam on the killer) -> respawn after the configured delay at the team spawn -> protection ends on attack. */
class DeathRespawnScenarioTest {

    @Test
    fun `death spectates the killer then respawns at the team spawn after 5s and protection ends on attack`() {
        scenario("death-respawn-flow") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow0 = player("yellow-0", team = "yellow") // the annihilation lane starts only with 3 teams
            startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow0 to "yellow"), stage = "annihilation-arena")
            val blueSpawn = blue.position
            val protection = sim.services.getOrThrow<ISpawnProtectionGateway>()

            // Red stands 6 blocks from blue's spawn and shoots blue (1 HP) dead.
            sim.setMaxHealth(blue, 1.0)
            red.moveTo(blueSpawn + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            val kill = eventsOf<PlayerKilled>().first { it.victim == blue.id }
            assertThat(kill.killer).isEqualTo(red.id)

            // Dead: spectator mode with the camera on the killer, untouchable, not respawned yet.
            assertThat(sim.spectator.isSpectating(blue.id)).isTrue()
            assertThat(sim.spectator.log).contains("start:${blue.id}", "watch:${blue.id}->${red.id}")
            assertThat(protection.isProtected(blue.id)).isTrue()
            assertThat(eventsOf<PlayerRespawned>().none { it.playerId == blue.id }).isTrue()

            // Death cam (2 s) ends in free-cam; the respawn lands 5 s (100 ticks) after the death.
            advance(45)
            assertThat(sim.spectator.cameraTargetOf(blue.id)).isNull()
            assertThat(sim.spectator.log).contains("release:${blue.id}")
            advance(60)
            val respawn = eventsOf<PlayerRespawned>().first { it.playerId == blue.id }
            assertThat(respawn.atTick - kill.atTick).isBetween(95L, 105L)
            assertThat(sim.spectator.isSpectating(blue.id)).isFalse()
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
            val blueSpawns = stage.spawnPoints.filter { it.team == TeamId("blue") }.map { it.position }
            assertThat(blueSpawns).`as`("respawned at one of blue's team spawns")
                .anyMatch { it.x == blue.position.x && it.z == blue.position.z }
            assertThat(hp(blue)).`as`("HP container restored on respawn").isEqualTo(1.0)

            // Spawn protection holds until the respawned player attacks.
            assertThat(protection.isProtected(blue.id)).isTrue()
            blue.holds("pistol")
            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            blue.faces(red)
            blue.rightClick()
            advance(10)
            assertThat(eventsOf<DamageDealt>().any { it.attacker == blue.id }).isTrue()
            assertThat(protection.isProtected(blue.id)).`as`("attacking ends spawn protection").isFalse()
        }
    }

    @Test
    fun `eliminated team members keep spectating a living player instead of the lobby`() {
        scenario("eliminated-spectates") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch(
                "annihilation",
                mapOf(red to "red", blue to "blue", yellow to "yellow"),
                stage = "annihilation-arena",
            )
            val before = blue.position

            sim.eventBus.publish(
                TeamEliminated(matchId = matchId, teamId = TeamId("blue"), survivingPlayers = setOf(blue.id), atTick = sim.tick),
            )
            advance(5)

            assertThat(sim.spectator.isSpectating(blue.id)).isTrue()
            assertThat(sim.spectator.cameraTargetOf(blue.id)).isIn(red.id, yellow.id)
            assertThat(blue.position).`as`("no lobby-stub teleport").isEqualTo(before)
        }
    }
}
