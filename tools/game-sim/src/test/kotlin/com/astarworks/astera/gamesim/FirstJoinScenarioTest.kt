package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Scenario: a brand-new player joins -> profile + starter grant exactly once; rejoin does not re-grant. */
class FirstJoinScenarioTest {

    @Test
    fun `first join creates the profile and grants starter gold once`() {
        scenario("first-join-starter-grant") {
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val newcomer = player("newcomer")
            advance(1)

            assertThat(eventsOf<FirstTimeProfileCreated>().map { it.playerId }).containsExactly(newcomer.id)
            assertThat(ledger.balance(newcomer.id, Currency.GOLD)).isEqualTo(100L)

            // Second join of the same player (reconnect): no second grant, no second event.
            sim.eventBus.publish(PlayerJoinedServer(playerId = newcomer.id))
            advance(1)

            assertThat(eventsOf<FirstTimeProfileCreated>()).hasSize(1)
            assertThat(ledger.balance(newcomer.id, Currency.GOLD)).isEqualTo(100L)
        }
    }
}
