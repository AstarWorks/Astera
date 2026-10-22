package com.astarworks.astera.economy.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.economy.application.intent.LobbyShopOpenIntentHandler
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseError
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseUseCase

/**
 * Wave 4.5 R3.5 (ADR-0027) — builds the `astera-shop` root command tree.
 *
 * Phase 1 ships one subcommand:
 *
 * ```
 * /astera-shop
 *   browse <shop-id>
 * ```
 *
 * `browse` resolves a [ShopId] and calls [ShopBrowseUseCase], which composes a
 * one-shot GUI from the matching [com.astarworks.astera.core.domain.shop.ShopSpec]'s
 * offers and asks the platform [com.astarworks.astera.core.application.port.outbound.IGuiAdapter]
 * to open it. The dynamic GUI emits `shop.purchase` intents back into
 * [com.astarworks.astera.economy.application.intent.ShopIntentHandler] when
 * the player clicks an offer.
 *
 * ## Executor semantics
 *
 * - Console invocations: [com.astarworks.astera.core.application.command.CommandContext.sender]
 *   is `null`, so the executor returns `astera.command.player-only`.
 * - On [ShopBrowseUseCase] failure each error variant maps to its own
 *   `astera.economy.shop.browse.*` message key. The `requirePermission`
 *   wrapper handles the permission gate before the executor body runs.
 * - The `shop-id` argument is validated by the use case via [ShopId]'s
 *   kebab-case regex; an illegal value surfaces as `astera.economy.shop.browse.invalid_id`.
 *
 * ## Why a separate root literal
 *
 * Phase 1's existing `astera-economy` tree (deposit / withdraw / transfer /
 * balance) is currency-only. `astera-shop` is its own root because (a) the
 * command vocabulary diverges, and (b) future shop subcommands (`open`,
 * `restock`, `list`) make more sense as siblings of `browse` than buried under
 * `astera-economy shop *`. Phase C wires this via `commandHandler.register(...)`.
 */
internal fun buildShopCommandSpec(
    browse: ShopBrowseUseCase,
    permService: IPermissionService,
): CommandSpec.Literal {
    val browseLiteral = CommandSpec.Literal(
        name = "browse",
        description = MessageKey("astera.economy.command.shop.browse"),
        subcommands = listOf(
            CommandSpec.Argument("shop-id", ArgumentType.StringArg()),
        ),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender
                ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val shopIdRaw = ctx.required<String>("shop-id")
            val shopId = runCatching { ShopId(shopIdRaw) }.getOrNull()
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.economy.shop.browse.invalid_id"),
                    mapOf("shop-id" to shopIdRaw),
                )

            when (val outcome = browse.execute(playerId, shopId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(
                    messageKey = outcome.error.toMessageKey(),
                    placeholders = mapOf("shop-id" to shopId.value),
                )
            }
        },
    ).requirePermission(
        permissionService = permService,
        permission = Permission("astera.economy.shop.browse"),
    )

    return CommandSpec.Literal(
        name = "astera-shop",
        description = MessageKey("astera.economy.command.shop.root"),
        subcommands = listOf(browseLiteral),
        // `/astera-shop` with no argument opens the lobby's default shop, the
        // same target as the lobby shop NPC / main-menu tile (`lobby.shop.open`).
        executor = lambda@ { ctx ->
            val playerId = ctx.sender
                ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val shopId = LobbyShopOpenIntentHandler.DEFAULT_LOBBY_SHOP_ID
            when (val outcome = browse.execute(playerId, shopId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(
                    messageKey = outcome.error.toMessageKey(),
                    placeholders = mapOf("shop-id" to shopId.value),
                )
            }
        },
    ).requirePermission(
        permissionService = permService,
        permission = Permission("astera.economy.shop.browse"),
    )
}

/** Maps each [ShopBrowseError] variant to a user-facing message key. */
private fun ShopBrowseError.toMessageKey(): MessageKey = when (this) {
    is ShopBrowseError.PlayerNotOnline ->
        MessageKey("astera.economy.shop.browse.player_not_online")
    is ShopBrowseError.ShopNotFound ->
        MessageKey("astera.economy.shop.browse.not_found")
    is ShopBrowseError.OpenRejected ->
        MessageKey("astera.economy.shop.browse.open_rejected")
}
