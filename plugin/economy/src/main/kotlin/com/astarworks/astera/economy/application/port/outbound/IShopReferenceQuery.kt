package com.astarworks.astera.economy.application.port.outbound

import com.astarworks.astera.core.domain.shop.ShopId

/**
 * Outbound port: read-only public projection of [ShopSpec] for external
 * consumers (Phase 4 Web admin REST `/api/shop/<id>/offers`, Discord bot
 * lookups, future SDK clients).
 *
 * Wave 5g (Core hardening) lands the port + a default in-process impl
 * (`DefaultShopReferenceQuery`) that wraps [IShopRegistry] and projects each
 * [ShopOffer] to [PublicShopOffer]. Phase 4 REST layer wraps **this** port,
 * never [IShopRegistry] directly — so the "what's exposed publicly" decision
 * stays in one place.
 *
 * Deliberately **does not** expose:
 * - current stock (security: scraping for low-stock arbitrage)
 * - per-player gating predicates (privacy)
 * - internal id fields beyond the public ShopId / ShopOfferId
 *
 * Per ADR-0071: the public projection is intentionally narrow; widening
 * requires explicit ADR amendment.
 */
public interface IShopReferenceQuery {

    /** Public projection of [shopId], or null if no such shop. */
    public fun resolvePublic(shopId: ShopId): PublicShopReference?

    /** Public projection of every shop. */
    public fun allPublic(): Collection<PublicShopReference>
}

/**
 * Public-safe projection of a single shop.
 *
 * Stock counts are intentionally absent — see [IShopReferenceQuery] KDoc.
 */
public data class PublicShopReference(
    public val id: ShopId,
    public val displayNameKey: String,
    public val offers: List<PublicShopOffer>,
)

/**
 * Public-safe projection of a single offer within a shop.
 */
public data class PublicShopOffer(
    public val offerId: String,
    public val itemId: String,
    public val priceLabel: String,
    public val available: Boolean,
)
