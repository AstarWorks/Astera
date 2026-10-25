package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockView
import com.astarworks.astera.core.application.port.outbound.IProfileCardService
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ECO-02 / ECO-04: buy a cosmetic with GOLD, equip it, see it on the profile; level rewards grant titles. */
class CosmeticsScenarioTest {

    private fun ScenarioScope.dispatch(who: SimEntity, key: String, vararg payload: Pair<String, String>) {
        val view = checkNotNull(sim.server.view(who.id))
        sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent(key, payload.toMap()))
    }

    @Test
    fun `buy a name colour with GOLD, it is worn and shows on the profile card`() {
        scenario("cosmetics-buy-equip") {
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val cosmetics = sim.services.getOrThrow<CosmeticService>()
            val buyer = player("buyer")
            advance(1)
            ledger.credit(buyer.id, Currency.GOLD, 1000L)

            // The profile card carries the entry point.
            dispatch(buyer, "lobby.profile.show")
            val view = checkNotNull(sim.server.view(buyer.id))
            val card = checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view))
            assertThat(card.slots.values.filterIsInstance<GuiSlot.Static>().mapNotNull { it.onLeftClick?.key })
                .contains("cosmetic.open")

            dispatch(buyer, "cosmetic.open")
            assertThat(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view)?.id?.value).isEqualTo("cosmetics")

            // crimson costs 300: the first click only asks, the second buys.
            dispatch(buyer, "cosmetic.click", "id" to "crimson")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(1000L)
            dispatch(buyer, "cosmetic.click", "id" to "crimson")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(700L)
            assertThat(sim.services.getOrThrow<IPlayerUnlockView>().unlocks(buyer.id)).contains("cosmetic:crimson")
            assertThat(cosmetics.equipped(buyer.id, CosmeticKind.NAME_COLOR)?.id).isEqualTo("crimson")

            // A second click on the worn cosmetic takes it off; owning it is free forever.
            dispatch(buyer, "cosmetic.click", "id" to "crimson")
            assertThat(cosmetics.equipped(buyer.id, CosmeticKind.NAME_COLOR)).isNull()
            dispatch(buyer, "cosmetic.click", "id" to "crimson")
            assertThat(cosmetics.equipped(buyer.id, CosmeticKind.NAME_COLOR)?.id).isEqualTo("crimson")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(700L)

            // The profile summary is no longer EMPTY.
            val summary = checkNotNull(sim.services.getOrThrow<IProfileCardService>().find(buyer.id)).cosmetics
            assertThat(summary.unlockedCount).isGreaterThanOrEqualTo(1)
            assertThat(summary.featuredCosmeticId).isEqualTo("crimson")
        }
    }

    @Test
    fun `level rewards grant a title that can be equipped, grant-only cosmetics are not sold and a short wallet is refused`() {
        scenario("cosmetics-titles") {
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val cosmetics = sim.services.getOrThrow<CosmeticService>()
            val alice = player("alice")
            advance(1)
            sim.stats.grantXp.execute(alice.id, 300L, XpSource.AdminGrant) // Lv3: title newcomer
            assertThat(cosmetics.owned(alice.id).map { it.id }).contains("newcomer", "rising-star")

            dispatch(alice, "cosmetic.open")
            dispatch(alice, "cosmetic.click", "id" to "newcomer")
            assertThat(cosmetics.titleSummary(alice.id).equippedTitleId).isEqualTo("newcomer")

            val before = ledger.balance(alice.id, Currency.GOLD)
            dispatch(alice, "cosmetic.click", "id" to "veteran") // Lv10 reward, not sold
            assertThat(cosmetics.owns(alice.id, checkNotNull(cosmetics.find("veteran")))).isFalse()
            dispatch(alice, "cosmetic.click", "id" to "portal") // 600 GOLD, alice has far less
            assertThat(cosmetics.owns(alice.id, checkNotNull(cosmetics.find("portal")))).isFalse()
            assertThat(ledger.balance(alice.id, Currency.GOLD)).isEqualTo(before)
        }
    }
}
