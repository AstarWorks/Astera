package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/** WP-G: respawn clears statuses, DoT kill credit, annihilation YAML config, elimination, spawn facing. */
class WpGScenarioTest {

    @Test
    fun `burn DoT kill credits the applier and death clears the status`() {
        scenario("wpg-dot-kill-credit") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            sim.setMaxHealth(blue, 3.0)

            val spec = checkNotNull(sim.services.getOrThrow<IStatusEffectRegistry>().find(StatusEffectId("burn")))
            val container = sim.services.getOrThrow<IStatusEffectContainer>()
            container.apply(
                StatusEffectInstance(
                    spec = spec,
                    targetPlayerId = blue.id,
                    remainingTicks = 400,
                    currentStacks = 1,
                    appliedAtTick = sim.tick,
                    sourcePlayerId = red.id,
                ),
            )
            advance(100)

            val kill = eventsOf<PlayerKilled>().first { it.victim == blue.id }
            assertThat(kill.killer).`as`("DoT kill is credited to the status applier").isEqualTo(red.id)
            assertThat(container.active(blue.id)).`as`("death clears statuses").isEmpty()
            assertThat(eventsOf<PlayerKilled>().filter { it.victim == blue.id })
                .`as`("no second death from lingering burn").hasSize(1)
            assertThat(eventsOf<PlayerRespawned>().any { it.playerId == blue.id }).isTrue()
        }
    }

    @Test
    fun `annihilation reads its phase timers from the yaml - 60s build`() {
        scenario("wpg-annihilation-yaml-config") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")

            advance(1000)
            assertThat(eventsOf<AnnihilationPhaseTransitioned>().filter { it.matchId == matchId }).isEmpty()
            advance(400)
            val t = eventsOf<AnnihilationPhaseTransitioned>().first { it.matchId == matchId }
            assertThat(t.oldPhase).isEqualTo(NexusPhase.Phase1Build)
        }
    }

    @Test
    fun `eliminated team members spectate instead of respawning`() {
        scenario("wpg-eliminated-spectates") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch(
                "annihilation",
                mapOf(red to "red", blue to "blue", yellow to "yellow"),
                stage = "annihilation-arena",
            )
            val respawnsBefore = eventsOf<PlayerRespawned>().count { it.playerId == blue.id }

            sim.eventBus.publish(
                TeamEliminated(
                    matchId = matchId,
                    teamId = TeamId("blue"),
                    survivingPlayers = setOf(blue.id),
                    atTick = sim.tick,
                ),
            )
            val health = sim.services.getOrThrow<IPlayerHealthGateway>()
            health.damage(blue.id, 1000.0, HealthChangeSource.AdminCommand)
            advance(20)
            assertThat(eventsOf<PlayerKilled>().any { it.victim == blue.id }).isTrue()
            assertThat(eventsOf<PlayerRespawned>().count { it.playerId == blue.id })
                .`as`("eliminated team does not respawn").isEqualTo(respawnsBefore)

            // A surviving team still respawns.
            health.damage(red.id, 1000.0, HealthChangeSource.AdminCommand)
            advance(20)
            assertThat(eventsOf<PlayerRespawned>().any { it.playerId == red.id }).isTrue()
        }
    }

    @Test
    fun `match spawns honour the spawn point yaw`() {
        scenario("wpg-spawn-facing") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")

            // blue spawns face yaw 119.4: direction = (-sin(yaw), 0, cos(yaw))
            assertThat(blue.direction.x).isEqualTo(-0.8712, Offset.offset(0.01))
            assertThat(blue.direction.z).isEqualTo(-0.4909, Offset.offset(0.01))
        }
    }
}
