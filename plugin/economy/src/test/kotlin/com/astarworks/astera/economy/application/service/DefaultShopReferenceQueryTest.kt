package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.application.port.outbound.PublicShopOffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultShopReferenceQuery] (ADR-0071 / Wave 5g).
 *
 * Key invariants exercised:
 *
 * 1. Stock **counts** never appear in the public projection — only the boolean
 *    [PublicShopOffer.available] is exposed. Verified via reflection on the
 *    declared properties so the test fails if a future field accidentally leaks
 *    a numeric remaining counter.
 * 2. [PublicShopOffer.available] reflects the authored stock shape:
 *    `Unlimited` → `true`, `Limited(remaining > 0)` → `true`,
 *    `Limited(remaining == 0)` → `false`.
 * 3. [DefaultShopReferenceQuery.resolvePublic] returns `null` for an unknown
 *    [ShopId], matching the [IShopReferenceQuery] contract.
 * 4. [DefaultShopReferenceQuery.allPublic] projects every registered shop and
 *    [priceLabel] is the documented `"<amount> <currency>"` format.
 */
public class DefaultShopReferenceQueryTest {

    private val registry = InMemoryShopRegistry()
    private val query = DefaultShopReferenceQuery(registry)

    @Test
    public fun `PublicShopOffer does not expose any stock-count field`() {
        // Reflection-based contract guard. If someone adds a numeric
        // `remaining` / `stock` field to PublicShopOffer, this test breaks
        // loudly — matching the security rationale in the port KDoc.
        val publicFields = PublicShopOffer::class.java.declaredFields
            .map { it.name }
            .toSet()
        assertThat(publicFields).containsExactlyInAnyOrder(
            "offerId",
            "itemId",
            "priceLabel",
            "available",
        )
    }

    @Test
    public fun `resolvePublic returns null for unknown shopId`() {
        val result = query.resolvePublic(ShopId("nowhere-shop"))

        assertThat(result).isNull()
    }

    @Test
    public fun `resolvePublic projects an Unlimited offer as available=true`() {
        val spec = ShopSpec(
            id = ShopId("infinite-shop"),
            displayKey = MessageKey("astera.shop.infinite.title"),
            offers = listOf(
                ShopOffer(
                    id = ShopOfferId("sword"),
                    itemId = ItemId("example-sword"),
                    price = ShopPrice(Currency.GOLD, 500L),
                    stock = ShopStock.Unlimited,
                ),
            ),
        )
        registry.register(spec)

        val result = query.resolvePublic(spec.id)

        assertThat(result).isNotNull
        assertThat(result!!.id).isEqualTo(spec.id)
        assertThat(result.displayNameKey).isEqualTo("astera.shop.infinite.title")
        assertThat(result.offers).hasSize(1)
        val offer = result.offers.single()
        assertThat(offer.offerId).isEqualTo("sword")
        assertThat(offer.itemId).isEqualTo("example-sword")
        assertThat(offer.priceLabel).isEqualTo("500 GOLD")
        assertThat(offer.available).isTrue
    }

    @Test
    public fun `resolvePublic projects a Limited offer with remaining gt 0 as available=true`() {
        val spec = ShopSpec(
            id = ShopId("limited-shop"),
            displayKey = MessageKey("astera.shop.limited.title"),
            offers = listOf(
                ShopOffer(
                    id = ShopOfferId("rare-bow"),
                    itemId = ItemId("rare-bow"),
                    price = ShopPrice(Currency.GEM, 25L),
                    stock = ShopStock.Limited(remaining = 7),
                ),
            ),
        )
        registry.register(spec)

        val result = query.resolvePublic(spec.id)

        assertThat(result).isNotNull
        val offer = result!!.offers.single()
        assertThat(offer.available).isTrue
        // The exact remaining count is intentionally absent from the
        // projection — only the availability boolean is visible.
        assertThat(offer.priceLabel).isEqualTo("25 GEM")
    }

    @Test
    public fun `resolvePublic projects a Limited offer with remaining=0 as available=false`() {
        val spec = ShopSpec(
            id = ShopId("sold-out-shop"),
            displayKey = MessageKey("astera.shop.sold_out.title"),
            offers = listOf(
                ShopOffer(
                    id = ShopOfferId("legendary"),
                    itemId = ItemId("legendary-sword"),
                    price = ShopPrice(Currency.STAR, 9999L),
                    stock = ShopStock.Limited(remaining = 0),
                ),
            ),
        )
        registry.register(spec)

        val result = query.resolvePublic(spec.id)

        assertThat(result).isNotNull
        val offer = result!!.offers.single()
        assertThat(offer.available).isFalse
    }

    @Test
    public fun `resolvePublic preserves offer ordering`() {
        val spec = ShopSpec(
            id = ShopId("ordered-shop"),
            displayKey = MessageKey("astera.shop.ordered.title"),
            offers = listOf(
                ShopOffer(
                    id = ShopOfferId("first"),
                    itemId = ItemId("a-item"),
                    price = ShopPrice(Currency.GOLD, 1L),
                    stock = ShopStock.Unlimited,
                ),
                ShopOffer(
                    id = ShopOfferId("second"),
                    itemId = ItemId("b-item"),
                    price = ShopPrice(Currency.GOLD, 2L),
                    stock = ShopStock.Limited(remaining = 3),
                ),
                ShopOffer(
                    id = ShopOfferId("third"),
                    itemId = ItemId("c-item"),
                    price = ShopPrice(Currency.GOLD, 3L),
                    stock = ShopStock.Limited(remaining = 0),
                ),
            ),
        )
        registry.register(spec)

        val result = query.resolvePublic(spec.id)!!

        assertThat(result.offers.map { it.offerId })
            .containsExactly("first", "second", "third")
        assertThat(result.offers.map { it.available })
            .containsExactly(true, true, false)
    }

    @Test
    public fun `allPublic returns a projection for every registered shop`() {
        val alpha = ShopSpec(
            id = ShopId("alpha-shop"),
            displayKey = MessageKey("astera.shop.alpha.title"),
            offers = emptyList(),
        )
        val bravo = ShopSpec(
            id = ShopId("bravo-shop"),
            displayKey = MessageKey("astera.shop.bravo.title"),
            offers = listOf(
                ShopOffer(
                    id = ShopOfferId("token"),
                    itemId = ItemId("token-item"),
                    price = ShopPrice(Currency.GOLD, 10L),
                    stock = ShopStock.Unlimited,
                ),
            ),
        )
        registry.register(alpha)
        registry.register(bravo)

        val results = query.allPublic()

        assertThat(results.map { it.id })
            .containsExactlyInAnyOrder(alpha.id, bravo.id)
    }

    @Test
    public fun `allPublic on empty registry returns an empty collection`() {
        val results = query.allPublic()

        assertThat(results).isEmpty()
    }
}
