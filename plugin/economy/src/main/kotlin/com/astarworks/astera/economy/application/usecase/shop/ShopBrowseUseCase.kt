package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
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
import org.slf4j.LoggerFactory

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
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
    ): Result<Unit, ShopBrowseError> {
        val player = server.findPlayer(playerId)
            ?: return Result.failure(ShopBrowseError.PlayerNotOnline)
        val shopSpec = registry.resolve(shopId)
            ?: return Result.failure(ShopBrowseError.ShopNotFound(shopId))

        val guiSpec = buildShopGui(playerId, shopSpec, pendingOffer)
        if (adapter.currentSpec(player)?.id == guiSpec.id) {
            adapter.update(player, guiSpec)
            return Result.success(Unit)
        }
        return if (adapter.open(player, guiSpec)) {
            Result.success(Unit)
        } else {
            Result.failure(ShopBrowseError.OpenRejected)
        }
    }

    /**
     * Rows of offers (9 per row, declared order) plus one footer row holding
     * the wallet. Each offer icon is the item itself (its own name, lore and
     * stats), with the price in the right currency, the live stock and the
     * click hint appended.
     */
    private fun buildShopGui(playerId: PlayerId, shopSpec: ShopSpec, pendingOffer: ShopOfferId?): GuiSpec {
        val visibleOffers = if (shopSpec.offers.size > MAX_OFFERS) {
            log.warn(
                "ShopSpec '{}' has {} offers; the GUI caps at {} - extra offers are hidden.",
                shopSpec.id.value,
                shopSpec.offers.size,
                MAX_OFFERS,
            )
            shopSpec.offers.take(MAX_OFFERS)
        } else {
            shopSpec.offers
        }
        val offerRows = ((visibleOffers.size + 8) / 9).coerceAtLeast(1)
        val size = GuiSize.entries.first { it.slotCount == (offerRows + 1) * 9 }

        return GuiSpec.build(
            id = GuiId("shop-${shopSpec.id.value}"),
            title = shopSpec.displayKey,
            size = size,
        ) {
            visibleOffers.forEachIndexed { index, offer ->
                slot(SlotIndex(index), offerSlot(playerId, shopSpec, offer, pendingOffer == offer.id))
            }
            for (col in 0 until 9) {
                slot(SlotIndex(size.slotCount - 9 + col), if (col == 4) walletSlot(playerId) else GuiSlot.Border(FOOTER_PANE))
            }
        }
    }

    private fun offerSlot(playerId: PlayerId, shopSpec: ShopSpec, offer: ShopOffer, pending: Boolean): GuiSlot.Static {
        val have = ledger.balance(playerId, offer.price.currency)
        val affordable = have >= offer.price.amount
        val remaining = when (val live = stock.currentStock(shopSpec.id, offer.id) ?: offer.stock) {
            is ShopStock.Unlimited -> null
            is ShopStock.Limited -> live.remaining
        }
        val soldOut = remaining != null && remaining <= 0
        val currencyName = currencyName(playerId, offer.price.currency)
        val priceArgs = mapOf(
            "amount" to offer.price.amount.toString(),
            "currency" to currencyName,
            "have" to have.toString(),
        )
        val lore = buildList {
            add(GuiText(SPACER))
            add(GuiText(if (affordable) PRICE_OK else PRICE_SHORT, priceArgs))
            if (remaining != null && !soldOut) add(GuiText(STOCK_LIMITED, mapOf("remaining" to remaining.toString())))
            add(
                GuiText(
                    when {
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
        /** Offers per shop: five rows; the sixth is the wallet footer. */
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
