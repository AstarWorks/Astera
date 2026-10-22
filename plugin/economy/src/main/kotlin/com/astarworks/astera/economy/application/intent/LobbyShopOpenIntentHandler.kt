package com.astarworks.astera.economy.application.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseError
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseUseCase
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — routes `lobby.shop.open` [GuiIntent] to
 * [ShopBrowseUseCase].
 *
 * Lives in `:plugin:economy` because [ShopBrowseUseCase] lives in Economy. ADR-0022 forbids `:plugin:core` from importing Economy types;
 * keeping the handler here preserves the boundary.
 *
 * # Default lobby shop
 *
 * The lobby compass / NPC routes call `lobby.shop.open` without a payload —
 * Phase 1 PoC defaults to [DEFAULT_LOBBY_SHOP_ID] (`example-shop`, the
 * Wave 4.5 demo content). When `payload["shop_id"]` is present (e.g. a
 * future NPC binds itself to a specific shop) the override wins.
 *
 * # Companion to `ShopIntentHandler`
 *
 * The Wave 4.5 [ShopIntentHandler] claims `"shop."` (`shop.purchase`); this
 * handler claims `"lobby.shop."` so the two namespaces don't collide. The
 * shop GUI itself still emits `shop.purchase` from its slots — only the
 * lobby-side "open the default shop" entry point lives here.
 */
public class LobbyShopOpenIntentHandler(
    private val browse: ShopBrowseUseCase,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_OPEN) {
            log.warn(
                "Unknown lobby.shop intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val overrideId = intent.payload[PAYLOAD_SHOP_ID]
        val shopId = if (!overrideId.isNullOrBlank()) {
            runCatching { ShopId(overrideId) }.getOrNull() ?: run {
                log.warn(
                    "lobby.shop.open intent has malformed shop_id '{}' from player {}",
                    overrideId, player.id,
                )
                send(player, MSG_SHOP_NOT_FOUND, mapOf("shop_id" to overrideId))
                return
            }
        } else {
            DEFAULT_LOBBY_SHOP_ID
        }

        when (val outcome = browse.execute(player.id, shopId)) {
            is Result.Success -> Unit // GUI open is its own user feedback
            is Result.Failure -> {
                val (key, placeholders) = outcome.error.toRendering(shopId)
                send(player, key, placeholders)
            }
        }
    }

    private fun send(player: IMcPlayer, key: MessageKey, placeholders: Map<String, String>) {
        playerGateway.sendMessage(player.id, messages.render(player.id, key, placeholders))
    }

    private fun ShopBrowseError.toRendering(shopId: ShopId): Pair<MessageKey, Map<String, String>> {
        val base = mapOf("shop_id" to shopId.value, "shop" to shopId.value)
        return when (this) {
            is ShopBrowseError.PlayerNotOnline ->
                MessageKey("astera.economy.shop.browse.player_not_online") to base
            is ShopBrowseError.ShopNotFound ->
                MSG_SHOP_NOT_FOUND to base
            is ShopBrowseError.OpenRejected ->
                MessageKey("astera.economy.shop.browse.open_rejected") to base
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.shop."
        public const val INTENT_OPEN: String = "lobby.shop.open"
        public const val PAYLOAD_SHOP_ID: String = "shop_id"

        /** Phase 1 PoC default — `content/shops/example-shop.yaml`. */
        public val DEFAULT_LOBBY_SHOP_ID: ShopId = ShopId("example-shop")

        private val MSG_SHOP_NOT_FOUND = MessageKey("astera.economy.shop.browse.not_found")
    }
}
