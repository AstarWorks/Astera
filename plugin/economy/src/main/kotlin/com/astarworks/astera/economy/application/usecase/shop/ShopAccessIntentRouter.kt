package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.economy.application.port.inbound.IShopAccessIntent
import com.astarworks.astera.economy.application.port.outbound.IShopReferenceQuery
import com.astarworks.astera.economy.application.port.outbound.IShopVendor
import com.astarworks.astera.economy.application.port.outbound.VendorId

/**
 * Single convergence point for every "open a shop" entry surface (ADR-0071).
 *
 * Wave 5g (Core hardening): the inbound [IShopAccessIntent] sealed hierarchy
 * has four variants — slash command, NPC right-click, chat alias, external
 * (Web admin / Discord / Velocity). This router resolves each to a concrete
 * [ShopId] (or a typed [ShopAccessError]) and delegates to the existing
 * [ShopBrowseUseCase].
 *
 * # Why a single router
 *
 * Adding a new entry surface in Phase 4 (e.g. QR-code scan, in-game terminal
 * block) means adding a sealed variant in [IShopAccessIntent] and a `when`
 * arm here. The browse use case stays untouched, and no surface re-implements
 * the "look up shop → check player online → compose GUI" sequence.
 *
 * # Failure shape
 *
 * Each resolution step has its own typed failure ([ShopAccessError]). A
 * delegated browse failure (e.g. player offline at GUI-open time) is wrapped
 * as [ShopAccessError.BrowseFailed] so callers can distinguish "router could
 * not resolve" from "browse use case rejected".
 */
public class ShopAccessIntentRouter(
    private val vendorBinding: IShopVendor,
    private val referenceQuery: IShopReferenceQuery,
    private val shopBrowse: ShopBrowseUseCase,
) {

    public fun route(intent: IShopAccessIntent): Result<Unit, ShopAccessError> = when (intent) {
        is IShopAccessIntent.OpenFromCommand ->
            delegate(intent.shopId) { shopBrowse.execute(intent.playerId, intent.shopId) }

        is IShopAccessIntent.OpenFromNpc -> {
            val shopId = vendorBinding.lookup(intent.vendorId)
                ?: return Result.failure(ShopAccessError.UnknownVendor(intent.vendorId))
            delegate(shopId) { shopBrowse.execute(intent.playerId, shopId) }
        }

        is IShopAccessIntent.OpenFromChat -> {
            val match = referenceQuery.allPublic().firstOrNull {
                it.id.value == intent.alias || it.displayNameKey == intent.alias
            } ?: return Result.failure(ShopAccessError.UnknownAlias(intent.alias))
            delegate(match.id) { shopBrowse.execute(intent.playerId, match.id) }
        }

        is IShopAccessIntent.OpenFromExternal -> {
            val shopId = runCatching { ShopId(intent.externalRef) }.getOrNull()
                ?: return Result.failure(ShopAccessError.InvalidExternalRef(intent.externalRef))
            delegate(shopId) { shopBrowse.execute(intent.playerId, shopId) }
        }
    }

    /**
     * Run [block] and lift any `ShopBrowseError` into [ShopAccessError.BrowseFailed]
     * so the router's caller works against a single sealed error hierarchy.
     */
    private inline fun delegate(
        shopId: ShopId,
        block: () -> Result<Unit, ShopBrowseError>,
    ): Result<Unit, ShopAccessError> = when (val r = block()) {
        is Result.Success -> Result.success(Unit)
        is Result.Failure -> Result.failure(ShopAccessError.BrowseFailed(shopId, r.error))
    }
}

/**
 * Expected failure modes of [ShopAccessIntentRouter.route].
 *
 * Sealed so callers (command executor, NPC listener, chat router, external
 * REST handler) can exhaustively map each variant to a `MessageKey` without
 * defensive `else` arms.
 */
public sealed class ShopAccessError {

    /** [IShopVendor] has no binding for the right-clicked [VendorId]. */
    public data class UnknownVendor(public val vendorId: VendorId) : ShopAccessError()

    /** [IShopReferenceQuery] has no shop whose id or displayNameKey matches the chat alias. */
    public data class UnknownAlias(public val alias: String) : ShopAccessError()

    /** External entry surface supplied a [String] that is not a syntactically valid [ShopId]. */
    public data class InvalidExternalRef(public val externalRef: String) : ShopAccessError()

    /**
     * Delegation to [ShopBrowseUseCase] resolved a [ShopId] but the browse use
     * case itself rejected (player offline, shop not registered, adapter
     * declined the GUI open). The wrapped [cause] preserves the original
     * sealed error for granular caller handling.
     */
    public data class BrowseFailed(
        public val shopId: ShopId,
        public val cause: ShopBrowseError,
    ) : ShopAccessError()
}
