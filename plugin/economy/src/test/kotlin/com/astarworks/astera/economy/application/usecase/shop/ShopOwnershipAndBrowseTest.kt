package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.PurchaseLimit
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.InMemoryShopPurchaseRepository
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.InMemoryShopStockRepository
import com.astarworks.astera.economy.application.usecase.marketplace.InMemoryItemRegistry
import com.astarworks.astera.economy.application.usecase.marketplace.simpleItemSpec
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeItemDelivery
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0211: once-only unlocks, free replicas, purchase history, shop paging and categories. */
class ShopOwnershipAndBrowseTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val shopId = ShopId("s")
    private val price = ShopPrice(Currency.STAR, 10L)

    private val carrying = mutableListOf<ItemId>()
    private lateinit var ledger: InMemoryCurrencyLedger
    private lateinit var delivery: FakeItemDelivery
    private lateinit var purchases: InMemoryShopPurchaseRepository
    private lateinit var shops: InMemoryShopRegistry
    private lateinit var gui: FakeGuiAdapter
    private lateinit var player: FakeMcPlayer
    private lateinit var purchase: ShopPurchaseUseCase
    private lateinit var browse: ShopBrowseUseCase

    @BeforeEach
    fun setUp() {
        val server = FakeMcServer()
        player = FakeMcPlayer(id = playerId)
        server.register(player)
        ledger = InMemoryCurrencyLedger().also { it.credit(playerId, Currency.STAR, 1_000L) }
        delivery = FakeItemDelivery()
        purchases = InMemoryShopPurchaseRepository()
        shops = InMemoryShopRegistry()
        gui = FakeGuiAdapter()
        val items = InMemoryItemRegistry().apply {
            register(simpleItemSpec(ItemId("sword"), "IRON_SWORD"))
            register(simpleItemSpec(ItemId("potion"), "POTION"))
            repeat(60) { register(simpleItemSpec(ItemId("w$it"), "STICK")) }
        }
        val stock = InMemoryShopStockRepository()
        val messages = FakeMessageRenderer()
        purchase = ShopPurchaseUseCase(
            shops, ledger, items, messages, server, FakeDomainEventBus(), stock, { delivery }, { false },
            purchases = purchases, carried = { carrying.toList() },
        )
        browse = ShopBrowseUseCase(shops, gui, server, ledger, stock, messages, purchases = purchases, carried = { carrying.toList() })
    }

    private fun offer(id: String, item: String, limit: PurchaseLimit = PurchaseLimit.ONCE, category: String? = null) =
        ShopOffer(ShopOfferId(id), ItemId(item), price, ShopStock.Unlimited, limit = limit, category = category)

    private fun register(vararg offers: ShopOffer) =
        shops.register(ShopSpec(shopId, MessageKey("t"), offers.toList()))

    private fun spec() = gui.currentSpec(player)!!
    private fun slot(i: Int) = spec().slotAt(SlotIndex(i))

    @Test
    fun `an owned unlock can be reclaimed after the authored stock becomes zero`() {
        val original = offer("o", "sword")
        register(original)
        purchase.execute(playerId, shopId, original.id)
        register(original.copy(stock = ShopStock.Limited(0)))

        val result = purchase.execute(playerId, shopId, original.id) as Result.Success

        assertThat(result.value.claimed).isTrue()
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(990L)
        assertThat(delivery.delivered).hasSize(2)
        assertThat(purchases.count(playerId, shopId, original.id)).isEqualTo(1)
    }

    @Test
    fun `a once-only unlock is paid once and re-clicking hands out a free copy`() {
        register(offer("o", "sword"))
        assertThat(purchase.execute(playerId, shopId, ShopOfferId("o"))).isInstanceOf(Result.Success::class.java)
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(990L)

        val again = purchase.execute(playerId, shopId, ShopOfferId("o")) as Result.Success

        assertThat(again.value.claimed).isTrue()
        assertThat(ledger.balance(playerId, Currency.STAR)).`as`("no second charge").isEqualTo(990L)
        assertThat(delivery.delivered).hasSize(2)
        assertThat(purchases.count(playerId, shopId, ShopOfferId("o"))).`as`("a claim is not a purchase").isEqualTo(1)
    }

    @Test
    fun `an owned unlock is not handed out while the player still carries the item`() {
        register(offer("o", "sword"))
        purchase.execute(playerId, shopId, ShopOfferId("o"))
        carrying += ItemId("sword")

        val result = purchase.execute(playerId, shopId, ShopOfferId("o"))

        assertThat(result.errorOrNull()).isEqualTo(ShopPurchaseError.AlreadyCarrying(ItemId("sword")))
        assertThat(delivery.delivered).hasSize(1)
    }

    @Test
    fun `an unlimited consumable is charged on every purchase`() {
        register(offer("o", "potion", limit = PurchaseLimit.UNLIMITED))
        repeat(3) { purchase.execute(playerId, shopId, ShopOfferId("o")) }
        assertThat(ledger.balance(playerId, Currency.STAR)).isEqualTo(970L)
        assertThat(delivery.delivered).hasSize(3)
    }

    @Test
    fun `history lists the newest purchases first`() {
        register(offer("a", "sword"), offer("b", "potion", limit = PurchaseLimit.UNLIMITED))
        purchase.execute(playerId, shopId, ShopOfferId("a"))
        Thread.sleep(5)
        purchase.execute(playerId, shopId, ShopOfferId("b"))

        assertThat(purchases.history(playerId, 10).map { it.offerId.value }).containsExactly("b", "a")
        assertThat(browse.history(playerId, shopId)).isInstanceOf(Result.Success::class.java)
        val s = spec()
        assertThat((s.slotAt(SlotIndex(0)) as GuiSlot.Static).itemId).isEqualTo(ItemId("potion"))
        assertThat((s.slotAt(SlotIndex(1)) as GuiSlot.Static).itemId).isEqualTo(ItemId("sword"))
    }

    @Test
    fun `an owned offer shows the owned badge instead of the price`() {
        register(offer("o", "sword"))
        purchase.execute(playerId, shopId, ShopOfferId("o"))
        browse.execute(playerId, shopId)
        val keys = (slot(0) as GuiSlot.Static).lore.map { it.key.value }
        assertThat(keys).contains("astera.economy.shop.gui.owned", "astera.economy.shop.gui.action.take_copy")
        assertThat(keys).doesNotContain("astera.economy.shop.gui.price")
    }

    @Test
    fun `more than 45 offers page, and the footer links to the next page`() {
        register(*Array(50) { offer("o$it", "w$it") })
        browse.execute(playerId, shopId)
        assertThat(spec().size.slotCount).isEqualTo(54)
        assertThat((slot(0) as GuiSlot.Static).itemId).isEqualTo(ItemId("w0"))
        val next = slot(45 + 8) as GuiSlot.Static
        assertThat(next.onLeftClick?.key).isEqualTo("shop.page")
        assertThat(next.onLeftClick?.payload).containsEntry("page", "1")

        browse.execute(playerId, shopId, page = 1)
        assertThat((slot(0) as GuiSlot.Static).itemId).isEqualTo(ItemId("w45"))
        assertThat((slot(5) as? GuiSlot.Static)).`as`("only 5 offers on page 2").isNull()
        assertThat((slot(9) as GuiSlot.Static).onLeftClick?.payload).`as`("prev page button").containsEntry("page", "0")
    }

    @Test
    fun `categories filter the list and the category button cycles through them`() {
        register(
            offer("g", "w1", category = "gun"),
            offer("s", "sword", category = "sword"),
            offer("g2", "w2", category = "gun"),
        )
        browse.execute(playerId, shopId)
        val cycle = slot(9 + 2) as GuiSlot.Static
        assertThat(cycle.onLeftClick?.payload).containsEntry("category", "gun")

        browse.execute(playerId, shopId, page = 0, category = "gun")

        assertThat((slot(0) as GuiSlot.Static).itemId).isEqualTo(ItemId("w1"))
        assertThat((slot(1) as GuiSlot.Static).itemId).isEqualTo(ItemId("w2"))
        assertThat(slot(2)).isNotInstanceOf(GuiSlot.Static::class.java)
    }

    @Test
    fun `a repaint after a purchase keeps the page the player is on`() {
        register(*Array(50) { offer("o$it", "w$it") })
        browse.execute(playerId, shopId)
        browse.execute(playerId, shopId, page = 1)

        browse.execute(playerId, shopId) // what the intent handler does after a purchase

        assertThat((slot(0) as GuiSlot.Static).itemId).isEqualTo(ItemId("w45"))
    }
}
