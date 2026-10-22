package com.astarworks.astera.economy.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.economy.application.intent.LobbyShopOpenIntentHandler
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseError
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseUseCase

/**
 * `/astera shop [shop-id]` (ADR-0027 R3.5, ADR-0184; formerly astera-shop [browse <id>]).
 *
 * Opens the shop GUI composed by [ShopBrowseUseCase]. Without an id it opens the lobby's
 * default shop, the same target as the lobby shop NPC / main-menu tile (`lobby.shop.open`).
 * Everyone may use it (`astera.economy.shop.browse`, the permission the former `browse` leaf
 * carried).
 *
 * - Console: `astera.command.player-only` (a GUI needs a player).
 * - [ShopBrowseUseCase] failures map to `astera.economy.shop.browse.*` keys; an illegal id
 *   surfaces as `astera.economy.shop.browse.invalid_id`.
 *
 * @param shopIds ids offered by tab completion (the shop registry, read at suggestion time).
 */
internal fun buildShopCommandSpec(
    browse: ShopBrowseUseCase,
    shopIds: () -> List<String> = { emptyList() },
): CommandSpec.Literal = CommandSpec.Literal(
    name = "shop",
    subcommands = listOf(
        CommandSpec.Argument(
            "shop-id",
            ArgumentType.StringArg(),
            completer = { _ -> shopIds() },
            optional = true,
        ),
    ),
    executor = lambda@ { ctx ->
        val playerId = ctx.sender
            ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
        val shopId = ctx.optional<String>("shop-id")?.let { raw ->
            runCatching { ShopId(raw) }.getOrNull()
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.economy.shop.browse.invalid_id"),
                    mapOf("shop" to raw),
                )
        } ?: LobbyShopOpenIntentHandler.DEFAULT_LOBBY_SHOP_ID
        when (val outcome = browse.execute(playerId, shopId)) {
            is Result.Success -> CommandResult.Success // the opened GUI is the reply
            is Result.Failure -> CommandResult.Failure(
                messageKey = outcome.error.toMessageKey(),
                placeholders = mapOf("shop" to shopId.value),
            )
        }
    },
).forEveryone(Permission("astera.economy.shop.browse"))

/** Maps each [ShopBrowseError] variant to a user-facing message key. */
private fun ShopBrowseError.toMessageKey(): MessageKey = when (this) {
    is ShopBrowseError.PlayerNotOnline ->
        MessageKey("astera.economy.shop.browse.player_not_online")
    is ShopBrowseError.ShopNotFound ->
        MessageKey("astera.economy.shop.browse.not_found")
    is ShopBrowseError.OpenRejected ->
        MessageKey("astera.economy.shop.browse.open_rejected")
}
