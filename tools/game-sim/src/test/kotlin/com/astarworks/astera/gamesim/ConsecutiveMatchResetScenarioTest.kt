package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Two consecutive matches on the same stage: whatever the first one leaves behind (broken objective blocks, burning /
 * wounded players, spectating state) must be gone when the second one starts.
 */
class ConsecutiveMatchResetScenarioTest {
    private val redCore = Vec3(-90.0, 65.0, 0.0)
    private val blueCore = Vec3(90.0, 65.0, 0.0)

    @Test
    fun `the second match on a stage starts with the objective blocks back and the players clean`() {
        scenario("consecutive-match-reset") {
            val attacker = player("attacker")
            val defender = player("defender")
            val teams = mapOf(attacker to "red", defender to "blue")
            val burn = checkNotNull(sim.services.getOrThrow<IStatusEffectRegistry>().find(StatusEffectId("burn")))
            val statuses = sim.services.getOrThrow<IStatusEffectContainer>()
            val health = sim.services.getOrThrow<IPlayerHealthGateway>()

            val first = startMatch("break-core", teams)
            // Leave the first match wounded and burning, with a destroyed core.
            statuses.apply(StatusEffectInstance(burn, defender.id, 400, 1, sim.tick))
            health.setHealth(defender.id, 1.0, HealthChangeSource.Other("test"))
            attacker.breaks(blueCore)
            assertThat(eventsOf<MatchEnded>().single().matchId).isEqualTo(first)
            advance(20)

            assertThat(statuses.has(defender.id, StatusEffectId("burn"))).`as`("status effects do not ride into the lobby").isFalse()
            assertThat(hp(defender)).`as`("HP is restored at match end").isGreaterThan(1.0)
            assertThat(sim.spectator.isSpectating(defender.id)).isFalse()

            val second = startMatch("break-core", teams)
            assertThat(second).isNotEqualTo(first)
            assertThat(eventsOf<MatchStarted>()).hasSize(2)
            assertThat(blockAt(redCore)).`as`("red core is back for match 2").isEqualTo("minecraft:emerald_block")
            assertThat(blockAt(blueCore)).`as`("destroyed blue core is back for match 2").isEqualTo("minecraft:emerald_block")
            assertThat(statuses.has(defender.id, StatusEffectId("burn"))).isFalse()
            assertThat(sim.isAlive(attacker.id)).isTrue()
            assertThat(sim.isAlive(defender.id)).isTrue()

            // And match 2 is a real match: the same objective still ends it.
            attacker.breaks(blueCore)
            assertThat(eventsOf<MatchEnded>()).hasSize(2)
            assertThat(eventsOf<MatchEnded>().last().matchId).isEqualTo(second)
        }
    }
}
