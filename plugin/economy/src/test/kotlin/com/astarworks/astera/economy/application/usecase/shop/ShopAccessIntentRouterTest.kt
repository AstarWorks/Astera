package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.application.port.inbound.IShopAccessIntent
import com.astarworks.astera.economy.application.port.outbound.VendorId
import com.astarworks.astera.economy.application.service.DefaultShopReferenceQuery
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.economy.testfixtures.FakeShopVendorBinding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [ShopAccessIntentRouter] (ADR-0071 / Wave 5g).
 *
 * One test per [IShopAccessIntent] variant. Each test asserts the router
 * delegated to the real [ShopBrowseUseCase] (observed indirectly through the
 * [FakeGuiAdapter] receiving an open call with a `GuiSpec` whose id encodes
 * the shop id `"shop-<shopId>"`), and that the [Result] is a success.
 *
 * Failure-path tests cover the three router-level errors: unknown vendor,
 * unknown alias, invalid external ref.
 */
public class ShopAccessIntentRouterTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val player = FakeMcPlayer(id = playerId, name = "tester")

    private val shopAlpha = ShopSpec(
        id = ShopId("alpha-shop"),
        displayKey = MessageKey("astera.shop.alpha.title"),
        offers = listOf(
            ShopOffer(
                id = ShopOfferId("iron-sword"),
                itemId = ItemId("example-sword"),
                price = ShopPrice(Currency.GOLD, 500L),
                stock = ShopStock.Unlimited,
            ),
        ),
    )

    private val shopBravo = ShopSpec(
        id = ShopId("bravo-shop"),
        displayKey = MessageKey("astera.shop.bravo.title"),
        offers = emptyList(),
    )

    private val registry = InMemoryShopRegistry().apply {
        register(shopAlpha)
        register(shopBravo)
    }
    private val server = FakeMcServer().apply { register(player) }
    private val gui = FakeGuiAdapter()

    private val browse = ShopBrowseUseCase(registry = registry, adapter = gui, server = server)
    private val vendorBinding = FakeShopVendorBinding()
    private val referenceQuery = DefaultShopReferenceQuery(registry)
    private val router = ShopAccessIntentRouter(vendorBinding, referenceQuery, browse)

    @Test
    public fun `OpenFromCommand routes directly to ShopBrowseUseCase with the carried shopId`() {
        val intent = IShopAccessIntent.OpenFromCommand(playerId, shopAlpha.id)

        val result = router.route(intent)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertOpened(shopAlpha.id)
    }

    @Test
    public fun `OpenFromNpc resolves vendor binding then delegates to browse`() {
        val vendorId = VendorId("villager-uuid-1")
        vendorBinding.bind(vendorId, shopBravo.id)
        val intent = IShopAccessIntent.OpenFromNpc(playerId, vendorId)

        val result = router.route(intent)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertOpened(shopBravo.id)
    }

    @Test
    public fun `OpenFromNpc with unbound vendor returns UnknownVendor`() {
        val vendorId = VendorId("nowhere-vendor")
        val intent = IShopAccessIntent.OpenFromNpc(playerId, vendorId)

        val result = router.route(intent)

        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(ShopAccessError.UnknownVendor(vendorId))
        assertThat(gui.history(player)).isEmpty()
    }

    @Test
    public fun `OpenFromChat matches by ShopId value then delegates to browse`() {
        val intent = IShopAccessIntent.OpenFromChat(playerId, alias = "alpha-shop")

        val result = router.route(intent)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertOpened(shopAlpha.id)
    }

    @Test
    public fun `OpenFromChat matches by displayNameKey then delegates to browse`() {
        val intent = IShopAccessIntent.OpenFromChat(playerId, alias = "astera.shop.bravo.title")

        val result = router.route(intent)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertOpened(shopBravo.id)
    }

    @Test
    public fun `OpenFromChat with no matching alias returns UnknownAlias`() {
        val intent = IShopAccessIntent.OpenFromChat(playerId, alias = "no-such-thing")

        val result = router.route(intent)

        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(ShopAccessError.UnknownAlias("no-such-thing"))
        assertThat(gui.history(player)).isEmpty()
    }

    @Test
    public fun `OpenFromExternal parses externalRef as a ShopId and delegates`() {
        val intent = IShopAccessIntent.OpenFromExternal(playerId, externalRef = "alpha-shop")

        val result = router.route(intent)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertOpened(shopAlpha.id)
    }

    @Test
    public fun `OpenFromExternal with syntactically invalid ref returns InvalidExternalRef`() {
        // ShopId requires lower-kebab-case; uppercase / spaces / underscores fail construction.
        val badRef = "NOT a valid shop id"
        val intent = IShopAccessIntent.OpenFromExternal(playerId, externalRef = badRef)

        val result = router.route(intent)

        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(ShopAccessError.InvalidExternalRef(badRef))
        assertThat(gui.history(player)).isEmpty()
    }

    /**
     * The dynamic GUI built by [ShopBrowseUseCase.buildShopGui] tags the spec's
     * id with `"shop-<shopId>"`. Asserting on the last open call's spec id is
     * the cheapest way to verify "this is the shop the router delegated to"
     * without having to crack open the spec internals.
     */
    private fun assertOpened(expected: ShopId) {
        val opens = gui.history(player).filterIsInstance<FakeGuiAdapter.GuiOperation.Open>()
        assertThat(opens).isNotEmpty
        val lastSpec: GuiSpec = opens.last().spec
        assertThat(lastSpec.id.value).isEqualTo("shop-${expected.value}")
    }
}
