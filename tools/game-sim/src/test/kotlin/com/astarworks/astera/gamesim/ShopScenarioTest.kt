package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Shop loop on the real wired graph (ADR-0199 / ADR-0202): a new player starts with the
 * starting GOLD, opens the lobby shop, sees prices in the right currency, needs a confirming
 * second click for an expensive offer, is told what is missing when short, and every
 * movement lands in the audit log.
 */
class ShopScenarioTest {

    private fun ScenarioScope.dispatch(who: SimEntity, key: String, vararg payload: Pair<String, String>) {
        val view = checkNotNull(sim.server.view(who.id))
        sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent(key, payload.toMap()))
    }

    private fun ScenarioScope.buyClick(who: SimEntity, offer: String) =
        dispatch(who, "shop.purchase", "shop_id" to "example-shop", "offer_id" to offer)

    @Test
    fun `shop command errors render the requested id in both languages`() {
        scenario("shop-command-errors") {
            val buyer = player("buyer")
            val messages = sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>()
            for (locale in listOf("en", "ja")) {
                sim.command("buyer", "lang $locale")
                for (id in listOf("missing-shop", "INVALID")) {
                    val run = sim.command("buyer", "shop $id")
                    val executed = run.outcome as com.astarworks.astera.core.application.command.CommandTreeRunner.Outcome.Executed
                    val failure = executed.result as com.astarworks.astera.core.application.command.CommandResult.Failure
                    val rendered = messages.render(buyer.id, failure.messageKey, failure.placeholders)
                    assertThat(rendered).contains(id).doesNotContain("{shop}")
                }
            }
        }
    }

    @Test
    fun `shop offer display key reaches the GUI through the production intent`() {
        scenario("shop-offer-display-key") {
            val buyer = player("buyer")
            val shops = sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IShopRegistry>()
            val shopId = com.astarworks.astera.core.domain.shop.ShopId("example-shop")
            val original = checkNotNull(shops.resolve(shopId))
            val key = com.astarworks.astera.core.domain.i18n.MessageKey("astera.economy.shop.gui.owned")
            shops.register(original.copy(offers = original.offers.map { it.copy(displayKey = key) }))
            dispatch(buyer, "lobby.shop.open")
            val view = checkNotNull(sim.server.view(buyer.id))
            val spec = checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view))
            assertThat((spec.slotAt(SlotIndex(0)) as GuiSlot.Static).displayName?.key).isEqualTo(key)
        }
    }

    @Test
    fun `new player opens the shop, confirms a purchase, gets the item and is told when short`() {
        scenario("shop-loop") {
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val buyer = player("buyer")
            advance(1)
            ledger.credit(buyer.id, Currency.GOLD, 50L) // + 150 starter GOLD = 200

            dispatch(buyer, "lobby.shop.open")
            val view = checkNotNull(sim.server.view(buyer.id))
            val spec = checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view))
            assertThat(spec.id.value).isEqualTo("shop-example-shop")
            val firstOffer = spec.slotAt(SlotIndex(0)) as GuiSlot.Static
            assertThat(firstOffer.lore.map { it.key.value }).contains("astera.economy.shop.gui.price")
            val starterItems = view.givenItems.toList()

            // dagger costs 150 GOLD: the first click only asks for confirmation.
            buyClick(buyer, "dagger")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(200L)
            assertThat(view.givenItems).containsExactlyElementsOf(starterItems) // starter grant only

            buyClick(buyer, "dagger")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(50L)
            assertThat(view.givenItems).containsExactlyElementsOf(starterItems + "dagger")

            // 200 GOLD fire-staff with 50 GOLD left: refused, nothing charged, shortfall named.
            buyClick(buyer, "fire-staff")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(50L)
            assertThat(view.messages.joinToString("\n")).contains("need 200").contains("you have 50").contains("150 short")

            // Transaction log: starter grant is not a CurrencyChanged, the purchase is.
            val movements = sim.services.getOrThrow<IAuditLogRepository>().recent(limit = 50)
                .filterIsInstance<AuditEvent.CurrencyMovement>().filter { it.player == buyer.id }
            assertThat(movements).anySatisfy {
                assertThat(it.delta).isEqualTo(-150L)
                assertThat(it.newBalance).isEqualTo(50L)
                assertThat(it.reason).isEqualTo("shop:example-shop/dagger")
            }
        }
    }

    @Test
    fun `a brand-new player can buy the recommended first offer with the starter GOLD alone`() {
        scenario("shop-first-purchase") {
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val buyer = player("fresh")
            advance(1)
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(150L)

            dispatch(buyer, "lobby.shop.open")
            val view = checkNotNull(sim.server.view(buyer.id))
            val starterItems = view.givenItems.toList()
            buyClick(buyer, "frost-staff") // 150 GOLD: the first click asks to confirm
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(150L)
            buyClick(buyer, "frost-staff")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isZero()
            assertThat(view.givenItems).containsExactlyElementsOf(starterItems + "frost-staff")
        }
    }

    @Test
    fun `an owned unlock is never charged twice and the purchase shows up in the player history`() {
        scenario("shop-ownership") {
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val buyer = player("owner")
            advance(1)
            ledger.credit(buyer.id, Currency.GOLD, 50L) // + 150 starter GOLD = 200
            dispatch(buyer, "lobby.shop.open")
            buyClick(buyer, "dagger")
            buyClick(buyer, "dagger") // confirming click: bought for 150
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(50L)

            buyClick(buyer, "dagger") // owned: free replica or "already carrying", never a second charge
            buyClick(buyer, "dagger")
            assertThat(ledger.balance(buyer.id, Currency.GOLD)).isEqualTo(50L)

            dispatch(buyer, "shop.history", "shop_id" to "example-shop")
            val view = checkNotNull(sim.server.view(buyer.id))
            val spec = checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view))
            assertThat(spec.id.value).isEqualTo("shop-history")
            assertThat((spec.slotAt(SlotIndex(0)) as GuiSlot.Static).itemId.value).isEqualTo("dagger")
            assertThat(spec.slotAt(SlotIndex(1))).isNotInstanceOf(GuiSlot.Static::class.java)

            dispatch(buyer, "shop.open", "shop_id" to "example-shop")
            assertThat(checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view)).id.value).isEqualTo("shop-example-shop")
        }
    }
}
