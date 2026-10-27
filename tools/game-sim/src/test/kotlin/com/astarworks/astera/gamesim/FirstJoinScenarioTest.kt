package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Scenario: a brand-new player joins -> profile + starter grant exactly once; rejoin does not re-grant. */
class FirstJoinScenarioTest {

    private companion object {
        const val STARTER_GOLD = 150L
    }

    @Test
    fun `first join creates the profile and delivers the starter weapons and the starter GOLD once`() {
        scenario("first-join-starter-grant") {
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val newcomer = player("newcomer")
            advance(1)

            assertThat(eventsOf<FirstTimeProfileCreated>().map { it.playerId }).containsExactly(newcomer.id)
            // ECO-11: the starter GOLD (content/balance/starter-grant.yaml) buys the recommended first shop offer.
            assertThat(ledger.balance(newcomer.id, Currency.GOLD)).isEqualTo(STARTER_GOLD)
            // The starter weapons (content/balance/starter-grant.yaml) are in the inventory, delivered once.
            assertThat(checkNotNull(sim.server.view(newcomer.id)).givenItems)
                .containsExactly("pistol", "knight-sword")

            // Second join of the same player (reconnect): no second grant, no second event.
            sim.eventBus.publish(PlayerJoinedServer(playerId = newcomer.id))
            advance(1)

            assertThat(eventsOf<FirstTimeProfileCreated>()).hasSize(1)
            assertThat(ledger.balance(newcomer.id, Currency.GOLD)).isEqualTo(STARTER_GOLD)
            assertThat(checkNotNull(sim.server.view(newcomer.id)).givenItems).hasSize(2)
        }
    }

    @Test
    fun `join stores the real Minecraft name, not a Player-uuid placeholder, and follows renames`() {
        scenario("profile-real-name") {
            val repo = sim.services.getOrThrow<IPlayerProfileRepository>()
            val hero = player("HeroSteve")
            advance(1)
            assertThat(repo.find(hero.id)?.displayName).isEqualTo("HeroSteve")

            // Same account reconnects under a new name: the last-known name follows.
            sim.detachPlayer(hero.id)
            sim.attachPlayer(hero.id, "HeroAlex")
            advance(1)
            assertThat(repo.find(hero.id)?.displayName).isEqualTo("HeroAlex")
        }
    }
}
