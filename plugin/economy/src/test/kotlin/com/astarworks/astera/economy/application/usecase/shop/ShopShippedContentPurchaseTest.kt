package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.service.StarterGrantLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.economy.application.intent.LobbyShopOpenIntentHandler
import com.astarworks.astera.economy.application.intent.ShopIntentHandler
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.InMemoryShopStockRepository
import com.astarworks.astera.economy.application.service.ShopLoaderService
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseUseCase
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The lobby shop as shipped: `content/shops` + `content/items` + the starter
 * grant in `content/balance`. Pins "a new player can open the shop, buy an
 * item with currency, and the item lands in their inventory" and the
 * insufficient-funds path, against the real content files.
 */
class ShopShippedContentPurchaseTest {

    private val content: Path = locateContentRoot()
    private val playerId = PlayerId(UUID.randomUUID())
    private val shopId = LobbyShopOpenIntentHandler.DEFAULT_LOBBY_SHOP_ID

    private lateinit var server: FakeMcServer
    private lateinit var player: FakeMcPlayer
    private lateinit var ledger: InMemoryCurrencyLedger
    private lateinit var shops: InMemoryShopRegistry
    private lateinit var stock: InMemoryShopStockRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var messages: FakeMessageRenderer
    private lateinit var purchase: ShopPurchaseUseCase
    private lateinit var gui: FakeGuiAdapter
    private lateinit var browse: ShopBrowseUseCase

    @BeforeEach
    fun setUp() {
        server = FakeMcServer()
        player = FakeMcPlayer(id = playerId)
        server.register(player)
        ledger = InMemoryCurrencyLedger()
        eventBus = FakeDomainEventBus()
        messages = FakeMessageRenderer()

        val items = ItemContents()
        assertThat(items.bootstrap(content.resolve("items")).failures).isEmpty()
        shops = InMemoryShopRegistry()
        stock = InMemoryShopStockRepository()
        val report = ShopLoaderService(shops, stock).loadFrom(content.resolve("shops"))
        assertThat(report.failures).isEmpty()

        purchase = ShopPurchaseUseCase(shops, ledger, items, messages, server, eventBus, stock)
        gui = FakeGuiAdapter()
        browse = ShopBrowseUseCase(shops, gui, server, ledger, stock, messages)

        // A brand-new player gets the shipped starter grant (what first join does).
        StarterGrantLoader().load(content.resolve("balance").resolve("starter-grant.yaml"))
            .walletGrants.forEach { (currency, amount) -> if (amount > 0) ledger.credit(playerId, currency, amount) }
    }

    @Test
    fun `every shipped offer references an item that exists in content items`() {
        val items = ItemContents().also { it.bootstrap(content.resolve("items")) }
        val missing = shops.all().flatMap { shop ->
            shop.offers.filter { items.find(it.itemId) == null }.map { "${shop.id.value}/${it.id.value}->${it.itemId.value}" }
        }
        assertThat(missing).isEmpty()
        assertThat(shops.resolve(shopId)).`as`("the lobby default shop ${shopId.value} must ship").isNotNull
    }

    @Test
    fun `a new player buys the cheapest offer - wallet debited and item delivered`() {
        val offer = shops.resolve(shopId)!!.offers.minBy { it.price.amount }
        val startBalance = ledger.balance(playerId, offer.price.currency)
        assertThat(startBalance).`as`("starter grant must afford the cheapest lobby offer").isGreaterThanOrEqualTo(offer.price.amount)

        val result = purchase.execute(playerId, shopId, offer.id)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val receipt = (result as Result.Success).value
        assertThat(receipt.price).isEqualTo(offer.price)
        assertThat(receipt.newBalance).isEqualTo(startBalance - offer.price.amount)
        assertThat(ledger.balance(playerId, offer.price.currency)).isEqualTo(startBalance - offer.price.amount)
        assertThat(player.given).hasSize(1)
        assertThat(eventBus.publishedOfType<CurrencyChanged>().single().delta).isEqualTo(-offer.price.amount)
    }

    @Test
    fun `an offer the wallet cannot cover fails with InsufficientFunds and changes nothing`() {
        val offer = shops.resolve(shopId)!!.offers.maxBy { it.price.amount }
        val balance = ledger.balance(playerId, offer.price.currency)
        // Drain to just under the price (the starter grant may already be below it).
        if (balance >= offer.price.amount) {
            ledger.debit(playerId, offer.price.currency, balance - (offer.price.amount - 1))
        }
        val before = ledger.balance(playerId, offer.price.currency)

        val result = purchase.execute(playerId, shopId, offer.id)

        val error = result.errorOrNull() as ShopPurchaseError.InsufficientFunds
        assertThat(error.currency).isEqualTo(offer.price.currency)
        assertThat(error.need).isEqualTo(offer.price.amount)
        assertThat(error.have).isLessThan(offer.price.amount)
        assertThat(ledger.balance(playerId, offer.price.currency)).isEqualTo(before).isLessThan(offer.price.amount)
        assertThat(player.given).isEmpty()
        assertThat(eventBus.publishedOfType<CurrencyChanged>()).isEmpty()
    }

    @Test
    fun `purchase intent tells the buyer what they bought and what is left`() {
        val handler = handler()
        val offer = shops.resolve(shopId)!!.offers.minBy { it.price.amount }
        val click = GuiIntent("shop.purchase", mapOf("shop_id" to shopId.value, "offer_id" to offer.id.value))

        handler.handle(click, player)
        if (offer.price.amount >= ShopIntentHandler.CONFIRM_AT) handler.handle(click, player) // confirming click

        val call = messages.calls.last { it.key == MessageKey("astera.economy.shop.purchase.success") }
        assertThat(call.placeholders).containsKeys("offer", "price", "currency", "balance")
        assertThat(call.placeholders["price"]).isEqualTo(offer.price.amount.toString())
        assertThat(call.placeholders["currency"]).isEqualTo("astera.economy.currency.${offer.price.currency.name.lowercase()}")
    }

    @Test
    fun `insufficient funds message names the missing currency`() {
        val handler = handler()
        val offer = shops.resolve(shopId)!!.offers.maxBy { it.price.amount }
        ledger.debit(playerId, offer.price.currency, ledger.balance(playerId, offer.price.currency))

        handler.handle(
            GuiIntent("shop.purchase", mapOf("shop_id" to shopId.value, "offer_id" to offer.id.value)),
            player,
        )

        val call = messages.calls.last { it.key == MessageKey("astera.economy.shop.purchase.insufficient_funds") }
        assertThat(call.placeholders["need"]).isEqualTo(offer.price.amount.toString())
        assertThat(call.placeholders["have"]).isEqualTo("0")
        assertThat(call.placeholders["short"]).isEqualTo(offer.price.amount.toString())
    }

    @Test
    fun `an expensive offer needs a confirming second click and the shop repaints with the new balance`() {
        val offer = shops.resolve(shopId)!!.offers.filter { it.price.amount >= ShopIntentHandler.CONFIRM_AT }
            .minByOrNull { it.price.amount } ?: return
        ledger.credit(playerId, offer.price.currency, offer.price.amount * 2)
        val before = ledger.balance(playerId, offer.price.currency)
        browse.execute(playerId, shopId)
        val handler = handler()
        val click = GuiIntent("shop.purchase", mapOf("shop_id" to shopId.value, "offer_id" to offer.id.value))

        handler.handle(click, player)
        assertThat(ledger.balance(playerId, offer.price.currency)).`as`("first click only arms").isEqualTo(before)
        assertThat(player.given).isEmpty()
        assertThat(messages.calls.map { it.key }).contains(MessageKey("astera.economy.shop.purchase.confirm"))

        handler.handle(click, player)
        assertThat(ledger.balance(playerId, offer.price.currency)).isEqualTo(before - offer.price.amount)
        assertThat(player.given).hasSize(1)
        // Repainted in place (Update, not a second Open) and the wallet line shows the new balance.
        assertThat(gui.history(player).filterIsInstance<FakeGuiAdapter.GuiOperation.Update>()).isNotEmpty
    }

    @Test
    fun `the shop gui shows price in the offer currency, a wallet and shortfall for unaffordable offers`() {
        ledger.debit(playerId, Currency.GOLD, ledger.balance(playerId, Currency.GOLD))
        ledger.debit(playerId, Currency.STAR, ledger.balance(playerId, Currency.STAR))
        assertThat(browse.execute(playerId, shopId)).isInstanceOf(Result.Success::class.java)
        val spec = gui.currentSpec(player)!!
        val offers = shops.resolve(shopId)!!.offers
        val statics = (0 until offers.size).map { spec.slotAt(com.astarworks.astera.core.domain.gui.SlotIndex(it)) as com.astarworks.astera.core.domain.gui.GuiSlot.Static }
        statics.forEachIndexed { i, slot ->
            assertThat(slot.lore.map { it.key.value }).`as`(offers[i].id.value).contains("astera.economy.shop.gui.price_short", "astera.economy.shop.gui.action.short")
            assertThat(slot.lore.first { it.key.value == "astera.economy.shop.gui.price_short" }.args["amount"]).isEqualTo(offers[i].price.amount.toString())
        }
        val footer = (0 until spec.size.slotCount).map { spec.slotAt(com.astarworks.astera.core.domain.gui.SlotIndex(it)) }.filterIsInstance<com.astarworks.astera.core.domain.gui.GuiSlot.Static>().filter { it.displayName?.key?.value == "astera.economy.shop.gui.wallet.title" }
        assertThat(footer).hasSize(1)
        assertThat(footer.single().lore).hasSize(Currency.entries.size)
    }

    @Test
    fun `unknown ids do not throw`() {
        val result = purchase.execute(playerId, ShopId("no-such-shop"), ShopOfferId("x"))
        assertThat(result.errorOrNull()).isEqualTo(ShopPurchaseError.ShopNotFound(ShopId("no-such-shop")))
    }

    private fun handler() = ShopIntentHandler(purchase, messages, FakePlayerGateway(), shops, ledger, browse)

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found")
    }
}
