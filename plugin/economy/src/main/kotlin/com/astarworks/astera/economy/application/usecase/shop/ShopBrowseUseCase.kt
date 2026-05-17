package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun execute(playerId: PlayerId, shopId: ShopId): Result<Unit, ShopBrowseError> {
        val player = server.findPlayer(playerId)
            ?: return Result.failure(ShopBrowseError.PlayerNotOnline)
        val shopSpec = registry.resolve(shopId)
            ?: return Result.failure(ShopBrowseError.ShopNotFound(shopId))

        val guiSpec = buildShopGui(shopSpec)
        return if (adapter.open(player, guiSpec)) {
            Result.success(Unit)
        } else {
            Result.failure(ShopBrowseError.OpenRejected)
        }
    }

    /**
     * Builds the dynamic chest GUI for [shopSpec]. Offers iterate in declared
     * order and are placed at slot indices `0..min(offers.size, MAX_OFFERS)-1`.
     * Offers beyond [MAX_OFFERS] are dropped with a WARN log — the operator
     * should split the shop or wait for the Phase 4 multi-page expansion.
     */
    private fun buildShopGui(shopSpec: ShopSpec): GuiSpec {
        val visibleOffers = if (shopSpec.offers.size > MAX_OFFERS) {
            log.warn(
                "ShopSpec '{}' has {} offers; Wave 4.5 caps the GUI at {} — extra offers are hidden.",
                shopSpec.id.value,
                shopSpec.offers.size,
                MAX_OFFERS,
            )
            shopSpec.offers.take(MAX_OFFERS)
        } else {
            shopSpec.offers
        }

        return GuiSpec.build(
            id = GuiId("shop-${shopSpec.id.value}"),
            title = shopSpec.displayKey,
            size = GuiSize.CHEST_27,
        ) {
            visibleOffers.forEachIndexed { index, offer ->
                slot(
                    SlotIndex(index),
                    GuiSlot.Static(
                        itemId = offer.itemId,
                        onClick = GuiIntent(
                            key = "shop.purchase",
                            payload = mapOf(
                                "shop_id" to shopSpec.id.value,
                                "offer_id" to offer.id.value,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    public companion object {
        /**
         * Wave 4.5 cap. `CHEST_27` is the Phase 1 PoC ceiling; the domain
         * already supports `CHEST_54` and a future paging variant can re-use
         * this use case with no signature change.
         */
        public const val MAX_OFFERS: Int = 27
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
