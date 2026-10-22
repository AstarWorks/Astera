package com.astarworks.astera.economy.application.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseUseCase
import com.astarworks.astera.economy.application.usecase.shop.ShopPurchaseError
import com.astarworks.astera.economy.application.usecase.shop.ShopPurchaseUseCase
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 4.5 R3.5 (ADR-0027) — routes `shop.*` [GuiIntent]s emitted by the
 * dynamically-built shop GUI into [ShopPurchaseUseCase] and renders the
 * outcome to the clicking player.
 *
 * Subscribed by `AsteraEconomyPlugin` via `IGuiIntentRouter.register("shop.")`.
 * Per the longest-prefix-wins routing rule, this handler claims the whole
 * `shop.` namespace; other plugins MUST NOT shadow it.
 *
 * ## Payload contract
 *
 * `shop.purchase` expects:
 *   - `shop_id` — kebab-case [ShopId.value]
 *   - `offer_id` — kebab-case [ShopOfferId.value]
 *
 * Missing / malformed payloads are logged at WARN and surfaced to the player as
 * a generic error message; this is a programming error in the GUI builder and
 * should never reach production.
 *
 * ## Two-click confirm
 *
 * An offer priced at [CONFIRM_AT] or more is not bought by the first click:
 * the buyer is told the price and the offer is marked in the GUI; a second
 * click on the same offer within [CONFIRM_WINDOW_MS] buys it. Cheaper offers,
 * and offers the buyer cannot afford (they just get the shortfall), skip the
 * confirm. After every attempt the open shop is repainted so balance and stock
 * are never stale.
 *
 * Unknown intent keys under the `shop.` prefix log at WARN and no-op — adding
 * a new key (e.g. `shop.refresh`, `shop.preview`) is a deliberate change here.
 */
public class ShopIntentHandler(
    private val purchase: ShopPurchaseUseCase,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val registry: IShopRegistry,
    private val ledger: ICurrencyLedger,
    private val browse: ShopBrowseUseCase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Pending(val shopId: ShopId, val offerId: ShopOfferId, val expiresAtMs: Long)

    private val pending = ConcurrentHashMap<PlayerId, Pending>()

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_PURCHASE -> handlePurchase(intent, player)
            else -> log.warn(
                "Unknown shop intent '{}' from player {} — payload={}",
                intent.key,
                player.id,
                intent.payload,
            )
        }
    }

    private fun handlePurchase(intent: GuiIntent, player: IMcPlayer) {
        val shopIdRaw = intent.payload[PAYLOAD_SHOP_ID]
        val offerIdRaw = intent.payload[PAYLOAD_OFFER_ID]
        if (shopIdRaw.isNullOrBlank() || offerIdRaw.isNullOrBlank()) {
            log.warn(
                "shop.purchase intent missing payload keys '{}' and/or '{}' from player {}: {}",
                PAYLOAD_SHOP_ID,
                PAYLOAD_OFFER_ID,
                player.id,
                intent.payload,
            )
            send(player, MSG_FAILURE_GENERIC, emptyMap())
            return
        }

        val shopId = runCatching { ShopId(shopIdRaw) }.getOrNull()
        val offerId = runCatching { ShopOfferId(offerIdRaw) }.getOrNull()
        if (shopId == null || offerId == null) {
            log.warn(
                "shop.purchase intent has malformed ids from player {}: shop_id='{}', offer_id='{}'",
                player.id,
                shopIdRaw,
                offerIdRaw,
            )
            send(player, MSG_FAILURE_GENERIC, emptyMap())
            return
        }

        val offer = registry.resolve(shopId)?.offer(offerId)
        if (offer != null && offer.price.amount >= CONFIRM_AT &&
            ledger.balance(player.id, offer.price.currency) >= offer.price.amount
        ) {
            val now = clock.millis()
            val armed = pending[player.id]
            if (armed == null || armed.shopId != shopId || armed.offerId != offerId || armed.expiresAtMs < now) {
                pending[player.id] = Pending(shopId, offerId, now + CONFIRM_WINDOW_MS)
                send(
                    player,
                    MSG_CONFIRM,
                    mapOf(
                        "offer_id" to offerId.value,
                        "price" to offer.price.amount.toString(),
                        "currency" to currencyName(player.id, offer.price.currency),
                    ),
                )
                browse.execute(player.id, shopId, pendingOffer = offerId)
                return
            }
        }
        pending.remove(player.id)

        when (val result = purchase.execute(player.id, shopId, offerId)) {
            is Result.Success -> {
                val receipt = result.value
                send(
                    player,
                    MSG_SUCCESS,
                    mapOf(
                        "shop_id" to shopId.value,
                        "offer_id" to offerId.value,
                        "offer" to receipt.itemName,
                        "price" to receipt.price.amount.toString(),
                        "currency" to currencyName(player.id, receipt.price.currency),
                        "balance" to receipt.newBalance.toString(),
                    ),
                )
            }
            is Result.Failure -> {
                val (key, placeholders) = result.error.toRendering(player.id, shopId, offerId)
                send(player, key, placeholders)
            }
        }
        browse.execute(player.id, shopId)
    }

    private fun currencyName(playerId: PlayerId, currency: com.astarworks.astera.core.domain.economy.Currency): String =
        messages.render(playerId, MessageKey("astera.economy.currency.${currency.name.lowercase()}"))

    private fun send(player: IMcPlayer, key: MessageKey, placeholders: Map<String, String>) {
        playerGateway.sendMessage(
            player.id,
            messages.render(player.id, key, placeholders),
        )
    }

    private fun ShopPurchaseError.toRendering(
        playerId: PlayerId,
        shopId: ShopId,
        offerId: ShopOfferId,
    ): Pair<MessageKey, Map<String, String>> {
        val base = mapOf("shop_id" to shopId.value, "offer_id" to offerId.value)
        return when (this) {
            is ShopPurchaseError.PlayerNotOnline ->
                MessageKey("astera.economy.shop.purchase.player_not_online") to base
            is ShopPurchaseError.ShopNotFound ->
                MessageKey("astera.economy.shop.purchase.shop_not_found") to base
            is ShopPurchaseError.OfferNotFound ->
                MessageKey("astera.economy.shop.purchase.offer_not_found") to base
            is ShopPurchaseError.OutOfStock ->
                MessageKey("astera.economy.shop.purchase.out_of_stock") to base
            is ShopPurchaseError.InsufficientFunds ->
                MessageKey("astera.economy.shop.purchase.insufficient_funds") to
                    (base + mapOf(
                        "currency" to currencyName(playerId, currency),
                        "need" to need.toString(),
                        "have" to have.toString(),
                        "short" to (need - have).toString(),
                    ))
            is ShopPurchaseError.DeliveryFailed ->
                MessageKey("astera.economy.shop.purchase.delivery_failed") to (base + ("reason" to reason))
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "shop."
        public const val INTENT_PURCHASE: String = "shop.purchase"

        /** Offers priced at or above this (in their own currency) need a confirming second click. */
        public const val CONFIRM_AT: Long = 100L
        public const val CONFIRM_WINDOW_MS: Long = 8_000L

        public const val PAYLOAD_SHOP_ID: String = "shop_id"
        public const val PAYLOAD_OFFER_ID: String = "offer_id"

        private val MSG_SUCCESS = MessageKey("astera.economy.shop.purchase.success")
        private val MSG_CONFIRM = MessageKey("astera.economy.shop.purchase.confirm")
        private val MSG_FAILURE_GENERIC = MessageKey("astera.economy.shop.purchase.failure_generic")
    }
}
