package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakeShopStockRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [ShopPurchaseUseCase] (Wave 4.5 R3.5 + Wave 5g stock
 * hardening).
 *
 * The headline regression is "spec-read-but-never-decremented": before
 * Wave 5g the use case only read `Limited.remaining` from the static
 * spec, so two buyers racing on a `Limited(N)` offer both saw the same
 * `N` and both received items. Wave 5g introduces
 * [com.astarworks.astera.core.application.port.outbound.IShopStockRepository]
 * with an authoritative atomic [decrementStock] call after a successful
 * debit and a debit-reversal on stock failure. The [`Limited(3) → 4th
 * fails`][limited_3_runs_dry_after_3_purchases] test pins that contract.
 */
public class ShopPurchaseUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val shopId = ShopId("test-shop")
    private val offerId = ShopOfferId("test-offer")
    private val unlimitedOfferId = ShopOfferId("unlimited-offer")
    private val itemId = ItemId("example-sword")
    private val price = ShopPrice(Currency.GOLD, 10L)

    private lateinit var server: FakeMcServer
    private lateinit var player: FakeMcPlayer
    private lateinit var ledger: InMemoryCurrencyLedger
    private lateinit var items: InMemoryItemRegistry
    private lateinit var shopRegistry: InMemoryShopRegistry
    private lateinit var stockRepository: FakeShopStockRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var messages: FakeMessageRenderer
    private lateinit var uc: ShopPurchaseUseCase

    @BeforeEach
    public fun setUp() {
        server = FakeMcServer()
        player = FakeMcPlayer(id = playerId)
        server.register(player)

        ledger = InMemoryCurrencyLedger()
        items = InMemoryItemRegistry().apply {
            register(simpleItemSpec(itemId, "IRON_SWORD"))
        }
        shopRegistry = InMemoryShopRegistry()
        stockRepository = FakeShopStockRepository()
        eventBus = FakeDomainEventBus()
        messages = FakeMessageRenderer()

        uc = ShopPurchaseUseCase(
            registry = shopRegistry,
            ledger = ledger,
            itemRegistry = items,
            messages = messages,
            server = server,
            eventBus = eventBus,
            stockRepository = stockRepository,
        )
    }

    private fun registerLimitedShop(initial: Int) {
        val spec = ShopSpec(
            id = shopId,
            displayKey = MessageKey("astera.economy.shop.test.title"),
            offers = listOf(
                ShopOffer(
                    id = offerId,
                    itemId = itemId,
                    price = price,
                    stock = ShopStock.Limited(initial),
                ),
            ),
        )
        shopRegistry.register(spec)
        // Loader-side seed; production: ShopLoaderService.loadFrom calls this.
        stockRepository.seed(shopId, offerId, initial)
    }

    private fun registerUnlimitedShop() {
        val spec = ShopSpec(
            id = shopId,
            displayKey = MessageKey("astera.economy.shop.test.title"),
            offers = listOf(
                ShopOffer(
                    id = unlimitedOfferId,
                    itemId = itemId,
                    price = price,
                    stock = ShopStock.Unlimited,
                ),
            ),
        )
        shopRegistry.register(spec)
        // Unlimited offers intentionally not seeded — currentStock returns null.
    }

    @Test
    public fun `Limited(3) runs dry after 3 purchases — 4th returns OutOfStock`() {
        registerLimitedShop(initial = 3)
        // Fund the player enough for 4 attempts so the test fails on the
        // stock check, not the wallet check.
        ledger.credit(playerId, Currency.GOLD, 4L * price.amount)

        // 3 successful purchases.
        repeat(3) { i ->
            val result = uc.execute(playerId, shopId, offerId)
            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(stockRepository.currentStock(shopId, offerId))
                .isEqualTo(ShopStock.Limited(2 - i))
        }

        // 4th must fail and must NOT charge the player.
        val balanceBefore = ledger.balance(playerId, Currency.GOLD)
        val fourth = uc.execute(playerId, shopId, offerId)

        assertThat(fourth.isFailure).isTrue
        assertThat(fourth.errorOrNull()).isEqualTo(ShopPurchaseError.OutOfStock(shopId, offerId))

        // Balance unchanged (debit was reversed via credit) and stock stayed at 0.
        assertThat(ledger.balance(playerId, Currency.GOLD)).isEqualTo(balanceBefore)
        assertThat(stockRepository.currentStock(shopId, offerId)).isEqualTo(ShopStock.Limited(0))

        // Player received exactly 3 items, not 4.
        assertThat(player.given).hasSize(3)

        // Exactly 3 CurrencyChanged events (one per success) — the 4th was
        // rolled back before publishing.
        assertThat(eventBus.publishedOfType<CurrencyChanged>()).hasSize(3)
    }

    @Test
    public fun `Unlimited offer ignores stock repository entirely`() {
        registerUnlimitedShop()
        ledger.credit(playerId, Currency.GOLD, 10L * price.amount)

        repeat(5) {
            val result = uc.execute(playerId, shopId, unlimitedOfferId)
            assertThat(result.isSuccess).isTrue
        }

        // Spec is Unlimited -> no row was seeded.
        assertThat(stockRepository.currentStock(shopId, unlimitedOfferId)).isNull()
        assertThat(player.given).hasSize(5)
        assertThat(eventBus.publishedOfType<CurrencyChanged>()).hasSize(5)
    }

    @Test
    public fun `InsufficientFunds short-circuits before touching stock`() {
        registerLimitedShop(initial = 3)
        // Buyer has nothing.

        val result = uc.execute(playerId, shopId, offerId)
        assertThat(result.errorOrNull()).isEqualTo(ShopPurchaseError.InsufficientFunds(Currency.GOLD, have = 0L, need = price.amount))
        // Stock unchanged — debit-failure short-circuit ran before decrementStock.
        assertThat(stockRepository.currentStock(shopId, offerId)).isEqualTo(ShopStock.Limited(3))
        assertThat(player.given).isEmpty()
    }

    @Test
    public fun `spec-Limited-but-unseeded offer fails with DeliveryFailed and reverses debit`() {
        // Authoring drift: spec advertises Limited but loader never seeded the
        // row (operator misconfig). Purchase must NOT charge the buyer.
        val spec = ShopSpec(
            id = shopId,
            displayKey = MessageKey("astera.economy.shop.test.title"),
            offers = listOf(
                ShopOffer(
                    id = offerId,
                    itemId = itemId,
                    price = price,
                    stock = ShopStock.Limited(5),
                ),
            ),
        )
        shopRegistry.register(spec)
        // INTENTIONALLY skip stockRepository.seed — simulates the drift.
        ledger.credit(playerId, Currency.GOLD, price.amount)
        val balanceBefore = ledger.balance(playerId, Currency.GOLD)

        val result = uc.execute(playerId, shopId, offerId)

        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isInstanceOf(ShopPurchaseError.DeliveryFailed::class.java)
        // Debit was reversed.
        assertThat(ledger.balance(playerId, Currency.GOLD)).isEqualTo(balanceBefore)
        assertThat(player.given).isEmpty()
    }

    /** Minimal in-memory [IItemRegistry] mirroring the marketplace tests' fake. */
    private class InMemoryItemRegistry : IItemRegistry {
        private val byId = mutableMapOf<ItemId, ItemSpec>()
        fun register(spec: ItemSpec) {
            byId[spec.id] = spec
        }
        override fun find(key: ItemId): ItemSpec? = byId[key]
        override fun all(): Collection<ItemSpec> = byId.values.toList()
    }
}

/**
 * Minimal valid [ItemSpec] for tests that only care about registry membership.
 * Mirrors the production `content/items/example-sword.yaml` defaults.
 */
private fun simpleItemSpec(id: ItemId, material: String): ItemSpec = ItemSpec(
    id = id,
    template = ItemTemplate.Vanilla(material = material),
    displayName = LocalizedText.EMPTY,
    lore = LocalizedLore.EMPTY,
    rarity = Rarity.COMMON,
    levelRequirement = 1,
    attributes = ItemAttributes.EMPTY,
    equipSlot = EquipSlot.MAINHAND,
    durability = DurabilityPolicy.DEFAULT,
    dropPolicy = DropPolicy.DEFAULT,
    enchantable = true,
    glint = false,
    skillBindings = emptyList(),
    pdcTags = emptyMap(),
)
