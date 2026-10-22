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
import com.astarworks.astera.testfixtures.FakeDomainEventBus
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
    private lateinit var delivery: com.astarworks.astera.testfixtures.FakeItemDelivery
    private lateinit var purchase: ShopPurchaseUseCase

    @BeforeEach
    fun setUp() {
        server = FakeMcServer()
        player = FakeMcPlayer(id = playerId)
        server.register(player)
        delivery = com.astarworks.astera.testfixtures.FakeItemDelivery()
        ledger = InMemoryCurrencyLedger()
        eventBus = FakeDomainEventBus()
        messages = FakeMessageRenderer()

        val items = ItemContents()
        assertThat(items.bootstrap(content.resolve("items")).failures).isEmpty()
        shops = InMemoryShopRegistry()
        stock = InMemoryShopStockRepository()
        val report = ShopLoaderService(shops, stock).loadFrom(content.resolve("shops"))
        assertThat(report.failures).isEmpty()

        purchase = ShopPurchaseUseCase(shops, ledger, items, messages, server, eventBus, stock) { delivery }

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
        assertThat(delivery.delivered).hasSize(1)
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

        assertThat(result.errorOrNull()).isEqualTo(ShopPurchaseError.InsufficientFunds(offer.price.currency))
        assertThat(ledger.balance(playerId, offer.price.currency)).isEqualTo(before).isLessThan(offer.price.amount)
        assertThat(delivery.delivered).isEmpty()
        assertThat(eventBus.publishedOfType<CurrencyChanged>()).isEmpty()
    }

    @Test
    fun `purchase intent tells the buyer what they bought and what is left`() {
        val handler = ShopIntentHandler(purchase, messages, FakePlayerGateway())
        val offer = shops.resolve(shopId)!!.offers.minBy { it.price.amount }

        handler.handle(
            GuiIntent("shop.purchase", mapOf("shop_id" to shopId.value, "offer_id" to offer.id.value)),
            player,
        )

        val call = messages.calls.last { it.key == MessageKey("astera.economy.shop.purchase.success") }
        assertThat(call.placeholders).containsKeys("offer", "price", "currency", "balance")
        assertThat(call.placeholders["price"]).isEqualTo(offer.price.amount.toString())
        assertThat(call.placeholders["currency"]).isEqualTo(offer.price.currency.name)
    }

    @Test
    fun `insufficient funds message names the missing currency`() {
        val handler = ShopIntentHandler(purchase, messages, FakePlayerGateway())
        val offer = shops.resolve(shopId)!!.offers.maxBy { it.price.amount }
        ledger.debit(playerId, offer.price.currency, ledger.balance(playerId, offer.price.currency))

        handler.handle(
            GuiIntent("shop.purchase", mapOf("shop_id" to shopId.value, "offer_id" to offer.id.value)),
            player,
        )

        val call = messages.calls.last { it.key == MessageKey("astera.economy.shop.purchase.insufficient_funds") }
        assertThat(call.placeholders["currency"]).isEqualTo(offer.price.currency.name)
    }

    @Test
    fun `unknown ids do not throw`() {
        val result = purchase.execute(playerId, ShopId("no-such-shop"), ShopOfferId("x"))
        assertThat(result.errorOrNull()).isEqualTo(ShopPurchaseError.ShopNotFound(ShopId("no-such-shop")))
    }

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
