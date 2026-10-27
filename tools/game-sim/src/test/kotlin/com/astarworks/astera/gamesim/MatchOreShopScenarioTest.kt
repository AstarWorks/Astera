package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * In-match ore shop on the real wired graph and the shipped `content/shops/match-shop.yaml`: a player with ore buys
 * an offer and the ore count drops, a player without ore is refused and keeps everything, the enemy team's shop NPC
 * is refused, base shop NPCs exist only while the match runs, and leftover ore is wiped at match end.
 */
class MatchOreShopScenarioTest {

    private fun ScenarioScope.dispatch(who: SimEntity, key: String, vararg payload: Pair<String, String>) {
        val view = checkNotNull(sim.server.view(who.id))
        sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent(key, payload.toMap()))
    }

    private fun ScenarioScope.items(who: SimEntity) = checkNotNull(sim.server.view(who.id)).givenItems

    @Test
    fun `ore buys an item in a match, no ore is refused, enemy shop is refused and ore is wiped at the end`() {
        scenario("match-ore-shop") {
            val spawner = sim.services.getOrThrow<IEntitySpawner>()
            val npcsBefore = spawner.all().size
            val red = player("red-buyer", team = "red")
            val blue = player("blue-buyer", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            assertThat(spawner.all().size).isEqualTo(npcsBefore + 2) // one shop NPC per team base

            // weapons tab index 0 is the dagger: 6 iron ore.
            repeat(7) { items(red) += "ore-iron" }
            val daggersBefore = items(red).count { it == "dagger" }
            dispatch(red, "matchshop.open", "team" to "red")
            dispatch(red, "matchshop.buy", "tab" to "weapons", "index" to "0")
            assertThat(items(red).count { it == "ore-iron" }).isEqualTo(1)
            assertThat(items(red).count { it == "dagger" }).isEqualTo(daggersBefore + 1)

            // 1 ore left, the dagger costs 6: refused, nothing taken, nothing given.
            dispatch(red, "matchshop.buy", "tab" to "weapons", "index" to "0")
            assertThat(items(red).count { it == "ore-iron" }).isEqualTo(1)
            assertThat(items(red).count { it == "dagger" }).isEqualTo(daggersBefore + 1)

            // A player carrying no ore at all is refused as well.
            val blueBefore = items(blue).toList()
            dispatch(blue, "matchshop.buy", "tab" to "weapons", "index" to "0")
            assertThat(items(blue)).containsExactlyElementsOf(blueBefore)

            // The red player may not use blue's shop NPC.
            val messagesBefore = checkNotNull(sim.server.view(red.id)).messages.size
            dispatch(red, "matchshop.open", "team" to "blue")
            assertThat(checkNotNull(sim.server.view(red.id)).messages.drop(messagesBefore).joinToString("\n")).contains("blue")

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            sim.step(10)
            assertThat(items(red).count { it == "ore-iron" }).isZero() // ore is match-scoped
            assertThat(spawner.all().size).isEqualTo(npcsBefore) // base shop NPCs are gone with the match
        }
    }

    @Test
    fun `every shipped match-shop offer and ore resolves to a real item`() {
        scenario("match-ore-shop-content") {
            val loader = com.astarworks.astera.economy.application.service.MatchShopLoaderService()
            val report = loader.loadFrom(GameSim.defaultContentDir().resolve("shops"))
            assertThat(report.failed).isZero()
            val registry = sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IItemRegistry>()
            val offers = loader.catalog.offers
            assertThat(offers).isNotEmpty()
            for (offer in offers) {
                assertThat(registry.find(offer.itemId)).describedAs("offer item %s", offer.itemId.value).isNotNull()
                for (ore in offer.price.keys) assertThat(registry.find(ore)).describedAs("ore %s", ore.value).isNotNull()
            }
        }
    }
}
