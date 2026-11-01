package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.economy.application.service.MatchShopLoaderService
import com.astarworks.astera.economy.domain.matchshop.MatchShopTab
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The in-match ore shop never sells a base weapon the player already carries (shipped `match-shop.yaml`, real wiring):
 * it is hidden from the listing and a direct buy is refused with the ore unspent. Upgrade tiers and blocks stay buyable,
 * and a player without the weapon sees and buys it.
 */
class MatchShopOwnedWeaponScenarioTest {

    private fun ScenarioScope.dispatch(who: SimEntity, key: String, vararg payload: Pair<String, String>) {
        val view = checkNotNull(sim.server.view(who.id))
        sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent(key, payload.toMap()))
    }

    private fun ScenarioScope.items(who: SimEntity) = checkNotNull(sim.server.view(who.id)).givenItems

    private fun ScenarioScope.index(tab: MatchShopTab, item: String): Int {
        val loader = MatchShopLoaderService()
        assertThat(loader.loadFrom(GameSim.defaultContentDir().resolve("shops")).failed).isZero()
        return loader.catalog.tab(tab).indexOfFirst { it.itemId.value == item }.also { assertThat(it).isNotNegative() }
    }

    private fun ScenarioScope.buy(who: SimEntity, tab: MatchShopTab, item: String) =
        dispatch(who, "matchshop.buy", "tab" to tab.key, "index" to index(tab, item).toString())

    private fun ScenarioScope.listed(who: SimEntity): List<String> {
        val spec = checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(checkNotNull(sim.server.view(who.id))))
        return (0 until 45).mapNotNull { (spec.slotAt(SlotIndex(it)) as? GuiSlot.Static)?.itemId?.value }
    }

    @Test
    fun `a carried base weapon is hidden and refused, upgrade tiers and blocks stay buyable, a player without it can buy it`() {
        scenario("match-shop-owned-weapon") {
            val red = player("red-owner", team = "red")
            val blue = player("blue-newcomer", team = "blue")
            startMatch("carry-tnt", mapOf(red to "red", blue to "blue"))
            advance(5)

            // Red carries a pistol (starter grant) and holds exactly the ore under test.
            for (p in listOf(red, blue)) items(p).removeAll { it == "pistol" || it.startsWith("ore-") }
            items(red) += "pistol"
            repeat(20) { items(red) += "ore-gold" }
            repeat(20) { items(red) += "ore-iron" }
            repeat(10) { items(blue) += "ore-gold" }

            dispatch(red, "matchshop.open", "team" to "red")
            assertThat(listed(red)).doesNotContain("pistol").contains("dagger")

            // Direct buy of the carried pistol (stale GUI): refused, ore unspent, no second pistol.
            val before = items(red).toList()
            buy(red, MatchShopTab.WEAPONS, "pistol")
            assertThat(items(red)).containsExactlyElementsOf(before)

            // Upgrade tier and a block offer still go through.
            buy(red, MatchShopTab.UPGRADES, "pistol-t2")
            assertThat(items(red).count { it == "pistol-t2" }).isEqualTo(1)
            assertThat(items(red).count { it == "pistol" }).isZero()
            val woolBefore = items(red).count { it == "wool-block" }
            buy(red, MatchShopTab.BLOCKS, "wool-block")
            assertThat(items(red).count { it == "wool-block" }).isGreaterThan(woolBefore)

            // Blue has no pistol: the offer is listed and the purchase works (ore-gold 5).
            dispatch(blue, "matchshop.open", "team" to "blue")
            assertThat(listed(blue)).contains("pistol")
            buy(blue, MatchShopTab.WEAPONS, "pistol")
            assertThat(items(blue).count { it == "pistol" }).isEqualTo(1)
            assertThat(items(blue).count { it == "ore-gold" }).isEqualTo(5)
        }
    }
}
