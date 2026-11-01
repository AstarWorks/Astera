package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.PurchaseLimit
import com.astarworks.astera.economy.application.service.InMemoryShopPurchaseRepository
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a [ShopId] to its registered [ShopSpec], composes a one-shot
 * [GuiSpec] from its offers, and asks the platform [IGuiAdapter] to open it
 * for the player.
 *
 * Wave 4.5 R3.5 use case (ADR-0027). The GUI is built **dynamically** rather
 * than loaded from `content/gui/`: stock / price changes on `ShopSpec` reflect
 * automatically without re-shipping any content YAML. Each offer becomes one
 * [GuiSlot.Static] slot in a 27-slot chest, with a `shop.purchase` [GuiIntent]
 * carrying `{shop_id, offer_id}` so the
 * [com.astarworks.astera.economy.application.intent.ShopIntentHandler] can
 * route the click to [ShopPurchaseUseCase].
 *
 * The use case is intentionally tiny: most of the heavy lifting (diff
 * rendering, click → intent translation) lives in the adapter and the
 * `IGuiIntentRouter`. The job here is to:
 *
 * 1. Confirm the player is online ([IMcServer.findPlayer] → non-null).
 * 2. Confirm the shop id is known to [IShopRegistry].
 * 3. Compose a `GuiSpec` from the offers (capped at 27, see [MAX_OFFERS]).
 * 4. Push the spec into the adapter and propagate its `false` (rejected)
 *    result as a typed [ShopBrowseError.OpenRejected].
 *
 * Errors are returned as a sealed [ShopBrowseError] so command executors and
 * any future inbound handlers (chat aliases, NPC interactions) can map them to
 * the right `MessageKey` without re-implementing the failure taxonomy.
 */
public class ShopBrowseUseCase(
    private val registry: IShopRegistry,
    private val adapter: IGuiAdapter,
    private val server: IMcServer,
    private val ledger: ICurrencyLedger,
    private val stock: IShopStockRepository,
    private val messages: IMessageRenderer,
    private val purchases: IShopPurchaseRepository = InMemoryShopPurchaseRepository(),
    private val carried: (PlayerId) -> List<ItemId> = { emptyList() },
    private val zone: ZoneId = ZoneOffset.UTC,
    private val onOpened: (PlayerId) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The page / category a player is looking at, so a repaint after a purchase stays where they were. */
    private data class View(val shopId: ShopId, val page: Int, val category: String?)

    private val views = ConcurrentHashMap<PlayerId, View>()

    /**
     * Opens [shopId] for [playerId], or - when the player already has this
     * shop open - repaints it in place (balance / stock changed after a
     * purchase). [pendingOffer] marks the offer that is waiting for its
     * confirming second click.
     */
    public fun execute(
        playerId: PlayerId,
        shopId: ShopId,
        pendingOffer: ShopOfferId? = null,
        page: Int? = null,
        category: String? = null,
    ): Result<Unit, ShopBrowseError> {
        val player = server.findPlayer(playerId)
            ?: return Result.failure(ShopBrowseError.PlayerNotOnline)
        val shopSpec = registry.resolve(shopId)
            ?: return Result.failure(ShopBrowseError.ShopNotFound(shopId))

        val open = adapter.currentSpec(player)?.id == GuiId("shop-${shopId.value}")
        val previous = views[playerId]?.takeIf { it.shopId == shopId && open }
        val view = when {
            page != null -> View(shopId, page, category)
            previous != null -> previous
            else -> View(shopId, 0, null)
        }
        val guiSpec = buildShopGui(playerId, shopSpec, pendingOffer, view)
        views[playerId] = view
        if (open) {
            adapter.update(player, guiSpec)
            onOpened(playerId)
            return Result.success(Unit)
        }
        return if (adapter.open(player, guiSpec)) {
            onOpened(playerId)
            Result.success(Unit)
        } else {
            Result.failure(ShopBrowseError.OpenRejected)
        }
    }

    /**
     * After a shops reload: repaints every open shop GUI from the new specs (same page and category), and
     * closes the ones whose shop no longer exists. Returns how many GUIs were touched.
     */
    public fun refreshOpen(): Int {
        var touched = 0
        for ((playerId, view) in views.entries.toList()) {
            val player = server.findPlayer(playerId) ?: continue
            if (adapter.currentSpec(player)?.id != GuiId("shop-${view.shopId.value}")) continue
            touched++
            if (registry.resolve(view.shopId) == null) adapter.close(player)
            else execute(playerId, view.shopId, page = view.page, category = view.category)
        }
        return touched
    }

    /**
     * One page of offers (9 per row, declared order, optionally filtered to one category) plus a footer row:
     * previous page, category cycle, wallet, history, next page. Each offer icon is the item itself (its own
     * name, lore and stats), with ownership, price, live stock and the click hint appended.
     */
    private fun buildShopGui(playerId: PlayerId, shopSpec: ShopSpec, pendingOffer: ShopOfferId?, view: View): GuiSpec {
        val categories = shopSpec.offers.mapNotNull { it.category }.distinct()
        val category = view.category?.takeIf { it in categories }
        val newcomer = shopSpec.offers.none { it.limit == PurchaseLimit.ONCE && purchases.count(playerId, shopSpec.id, it.id) > 0 }
        // A newcomer sees the recommended first buy at the top (stable sort keeps the authored order otherwise).
        val filtered = shopSpec.offers.filter { category == null || it.category == category }
            .let { list -> if (newcomer) list.sortedByDescending { it.recommended } else list }
        val pages = ((filtered.size + MAX_OFFERS - 1) / MAX_OFFERS).coerceAtLeast(1)
        val page = view.page.coerceIn(0, pages - 1)
        val visibleOffers = filtered.drop(page * MAX_OFFERS).take(MAX_OFFERS)
        val offerRows = ((visibleOffers.size + 8) / 9).coerceAtLeast(1)
        val size = GuiSize.entries.first { it.slotCount == (offerRows + 1) * 9 }
        val footer = size.slotCount - 9
        val cycle = (listOf<String?>(null) + categories)
        val nextCategory = cycle[(cycle.indexOf(category) + 1) % cycle.size]

        return GuiSpec.build(
            id = GuiId("shop-${shopSpec.id.value}"),
            title = shopSpec.displayKey,
            size = size,
        ) {
            visibleOffers.forEachIndexed { index, offer ->
                slot(SlotIndex(index), offerSlot(playerId, shopSpec, offer, pendingOffer == offer.id, newcomer && offer.recommended))
            }
            for (col in 0 until 9) slot(SlotIndex(footer + col), GuiSlot.Border(FOOTER_PANE))
            if (page > 0) slot(SlotIndex(footer), pageSlot(shopSpec, PREV_ICON, PAGE_PREV, page - 1, category, page, pages))
            if (categories.isNotEmpty()) {
                slot(SlotIndex(footer + 2), categorySlot(playerId, shopSpec, category, nextCategory))
            }
            slot(SlotIndex(footer + 1), cosmeticsSlot())
            slot(SlotIndex(footer + 4), walletSlot(playerId))
            slot(SlotIndex(footer + 6), historySlot(shopSpec))
            if (page < pages - 1) {
                slot(SlotIndex(footer + 8), pageSlot(shopSpec, NEXT_ICON, PAGE_NEXT, page + 1, category, page + 2, pages))
            }
        }
    }

    private fun pageSlot(
        shopSpec: ShopSpec,
        icon: ItemId,
        title: MessageKey,
        target: Int,
        category: String?,
        shownPage: Int,
        pages: Int,
    ): GuiSlot.Static = GuiSlot.Static(
        itemId = icon,
        displayName = GuiText(title, mapOf("page" to shownPage.toString(), "pages" to pages.toString())),
        onLeftClick = GuiIntent(
            key = INTENT_PAGE,
            payload = buildMap {
                put("shop_id", shopSpec.id.value)
                put("page", target.toString())
                if (category != null) put("category", category)
            },
        ),
    )

    private fun categorySlot(playerId: PlayerId, shopSpec: ShopSpec, current: String?, next: String?): GuiSlot.Static =
        GuiSlot.Static(
            itemId = CATEGORY_ICON,
            displayName = GuiText(CATEGORY_TITLE, mapOf("category" to categoryName(playerId, current))),
            lore = listOf(GuiText(CATEGORY_NEXT, mapOf("category" to categoryName(playerId, next)))),
            onLeftClick = GuiIntent(
                key = INTENT_PAGE,
                payload = buildMap {
                    put("shop_id", shopSpec.id.value)
                    put("page", "0")
                    if (next != null) put("category", next)
                },
            ),
        )

    /** The way into the cosmetics menu (ADR-0269): the GOLD sink beyond weapons is reachable from the shop itself. */
    private fun cosmeticsSlot(): GuiSlot.Static = GuiSlot.Static(
        itemId = COSMETICS_ICON,
        displayName = GuiText(COSMETICS_TITLE),
        lore = listOf(GuiText(COSMETICS_HINT)),
        onLeftClick = GuiIntent(key = INTENT_COSMETICS),
    )

    private fun historySlot(shopSpec: ShopSpec): GuiSlot.Static = GuiSlot.Static(
        itemId = HISTORY_ICON,
        displayName = GuiText(HISTORY_TITLE),
        lore = listOf(GuiText(HISTORY_HINT)),
        onLeftClick = GuiIntent(key = INTENT_HISTORY, payload = mapOf("shop_id" to shopSpec.id.value)),
    )

    private fun categoryName(playerId: PlayerId, category: String?): String =
        messages.render(playerId, MessageKey("astera.economy.shop.category.${category ?: "all"}"))

    /** The player's own purchase history (newest first) as a read-only GUI with a way back to the shop. */
    public fun history(playerId: PlayerId, shopId: ShopId): Result<Unit, ShopBrowseError> {
        val player = server.findPlayer(playerId) ?: return Result.failure(ShopBrowseError.PlayerNotOnline)
        val shopSpec = registry.resolve(shopId) ?: return Result.failure(ShopBrowseError.ShopNotFound(shopId))
        val records = purchases.history(playerId, MAX_OFFERS)
        val rows = ((records.size + 8) / 9).coerceAtLeast(1)
        val size = GuiSize.entries.first { it.slotCount == (rows + 1) * 9 }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val spec = GuiSpec.build(id = GuiId("shop-history"), title = HISTORY_GUI_TITLE, size = size) {
            records.forEachIndexed { index, r ->
                slot(
                    SlotIndex(index),
                    GuiSlot.Static(
                        itemId = r.itemId,
                        lore = listOf(
                            GuiText(SPACER),
                            GuiText(
                                HISTORY_LINE,
                                mapOf(
                                    "when" to fmt.format(r.purchasedAt),
                                    "amount" to r.price.amount.toString(),
                                    "currency" to currencyName(playerId, r.price.currency),
                                ),
                            ),
                        ),
                    ),
                )
            }
            val footer = size.slotCount - 9
            for (col in 0 until 9) slot(SlotIndex(footer + col), GuiSlot.Border(FOOTER_PANE))
            slot(
                SlotIndex(footer + 4),
                GuiSlot.Static(
                    itemId = BACK_ICON,
                    displayName = GuiText(HISTORY_BACK),
                    onLeftClick = GuiIntent(key = INTENT_OPEN, payload = mapOf("shop_id" to shopSpec.id.value)),
                ),
            )
        }
        return if (adapter.currentSpec(player)?.id == spec.id) {
            adapter.update(player, spec)
            Result.success(Unit)
        } else if (adapter.open(player, spec)) {
            Result.success(Unit)
        } else {
            Result.failure(ShopBrowseError.OpenRejected)
        }
    }

    private fun offerSlot(playerId: PlayerId, shopSpec: ShopSpec, offer: ShopOffer, pending: Boolean, recommended: Boolean): GuiSlot.Static {
        val have = ledger.balance(playerId, offer.price.currency)
        val affordable = have >= offer.price.amount
        val remaining = when (val live = stock.currentStock(shopSpec.id, offer.id) ?: offer.stock) {
            is ShopStock.Unlimited -> null
            is ShopStock.Limited -> live.remaining
        }
        val owned = offer.limit == PurchaseLimit.ONCE && purchases.count(playerId, shopSpec.id, offer.id) > 0
        val carrying = owned && offer.itemId in carried(playerId)
        val soldOut = !owned && remaining != null && remaining <= 0
        val currencyName = currencyName(playerId, offer.price.currency)
        val priceArgs = mapOf(
            "amount" to offer.price.amount.toString(),
            "currency" to currencyName,
            "have" to have.toString(),
        )
        val lore = buildList {
            add(GuiText(SPACER))
            if (recommended) add(GuiText(RECOMMENDED_BADGE))
            if (owned) add(GuiText(OWNED_BADGE)) else add(GuiText(if (affordable) PRICE_OK else PRICE_SHORT, priceArgs))
            if (!owned && remaining != null && !soldOut) add(GuiText(STOCK_LIMITED, mapOf("remaining" to remaining.toString())))
            add(
                GuiText(
                    when {
                        carrying -> ACTION_CARRYING
                        owned -> ACTION_TAKE_COPY
                        soldOut -> ACTION_SOLD_OUT
                        !affordable -> ACTION_SHORT
                        pending -> ACTION_CONFIRM
                        else -> ACTION_BUY
                    },
                    mapOf("missing" to (offer.price.amount - have).toString(), "currency" to currencyName),
                ),
            )
        }
        return GuiSlot.Static(
            itemId = offer.itemId,
            displayName = offer.displayKey?.let { GuiText(it) },
            onLeftClick = GuiIntent(
                key = "shop.purchase",
                payload = mapOf("shop_id" to shopSpec.id.value, "offer_id" to offer.id.value),
            ),
            lore = lore,
        )
    }

    private fun walletSlot(playerId: PlayerId): GuiSlot.Static = GuiSlot.Static(
        itemId = WALLET_ICON,
        displayName = GuiText(WALLET_TITLE),
        lore = Currency.entries.map { c ->
            GuiText(
                WALLET_LINE,
                mapOf("currency" to currencyName(playerId, c), "amount" to ledger.balance(playerId, c).toString()),
            )
        },
    )

    private fun currencyName(playerId: PlayerId, currency: Currency): String =
        messages.render(playerId, MessageKey("astera.economy.currency.${currency.name.lowercase()}"))

    public companion object {
        /** Offers per page: five rows; the sixth is the footer (paging, category, wallet, history). */
        public const val MAX_OFFERS: Int = 45

        private val FOOTER_PANE = ItemId("gui-pane-dark")
        private val WALLET_ICON = ItemId("gui-admin-currency")
        private val SPACER = MessageKey("astera.economy.shop.gui.spacer")
        private val PRICE_OK = MessageKey("astera.economy.shop.gui.price")
        private val PRICE_SHORT = MessageKey("astera.economy.shop.gui.price_short")
        private val STOCK_LIMITED = MessageKey("astera.economy.shop.gui.stock")
        private val ACTION_BUY = MessageKey("astera.economy.shop.gui.action.buy")
        private val ACTION_CONFIRM = MessageKey("astera.economy.shop.gui.action.confirm")
        private val ACTION_SHORT = MessageKey("astera.economy.shop.gui.action.short")
        private val ACTION_SOLD_OUT = MessageKey("astera.economy.shop.gui.action.sold_out")
        private val ACTION_CARRYING = MessageKey("astera.economy.shop.gui.action.carrying")
        private val ACTION_TAKE_COPY = MessageKey("astera.economy.shop.gui.action.take_copy")
        private val RECOMMENDED_BADGE = MessageKey("astera.economy.shop.gui.recommended")
        private val OWNED_BADGE = MessageKey("astera.economy.shop.gui.owned")
        private val PAGE_PREV = MessageKey("astera.economy.shop.gui.page.prev")
        private val PAGE_NEXT = MessageKey("astera.economy.shop.gui.page.next")
        private val CATEGORY_TITLE = MessageKey("astera.economy.shop.gui.category.title")
        private val CATEGORY_NEXT = MessageKey("astera.economy.shop.gui.category.next")
        private val HISTORY_TITLE = MessageKey("astera.economy.shop.gui.history.title")
        private val HISTORY_HINT = MessageKey("astera.economy.shop.gui.history.hint")
        private val HISTORY_GUI_TITLE = MessageKey("astera.economy.shop.gui.history.gui_title")
        private val HISTORY_LINE = MessageKey("astera.economy.shop.gui.history.line")
        private val HISTORY_BACK = MessageKey("astera.economy.shop.gui.history.back")
        private val PREV_ICON = ItemId("gui-admin-prev")
        private val NEXT_ICON = ItemId("gui-admin-next")
        private val CATEGORY_ICON = ItemId("gui-admin-cat-all")
        private val HISTORY_ICON = ItemId("gui-admin-page")
        private val BACK_ICON = ItemId("gui-admin-back")
        private val COSMETICS_ICON = ItemId("gui-tile-customize")
        private val COSMETICS_TITLE = MessageKey("astera.economy.shop.gui.cosmetics.title")
        private val COSMETICS_HINT = MessageKey("astera.economy.shop.gui.cosmetics.hint")

        /** Handled by the cosmetics menu (`cosmetic.` prefix), not by the shop handler. */
        public const val INTENT_COSMETICS: String = "cosmetic.open"

        public const val INTENT_PAGE: String = "shop.page"
        public const val INTENT_HISTORY: String = "shop.history"
        public const val INTENT_OPEN: String = "shop.open"

        private val WALLET_TITLE = MessageKey("astera.economy.shop.gui.wallet.title")
        private val WALLET_LINE = MessageKey("astera.economy.shop.gui.wallet.line")
    }
}

/** Expected failure modes of [ShopBrowseUseCase.execute]. */
public sealed class ShopBrowseError {
    /** The target [PlayerId] has no online session on this server. */
    public data object PlayerNotOnline : ShopBrowseError()

    /** No [ShopSpec] is registered under [id]. */
    public data class ShopNotFound(public val id: ShopId) : ShopBrowseError()

    /**
     * The adapter declined to open the spec (invalid item refs, race with
     * disconnect, etc.). The adapter logs the underlying cause; from the use
     * case's perspective only the boolean outcome matters.
     */
    public data object OpenRejected : ShopBrowseError()
}
