package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greaterEq

/**
 * `shop_stock` table — mutable per-offer stock for [com.astarworks.astera.core.domain.shop.ShopOffer]s
 * whose [com.astarworks.astera.core.domain.shop.ShopOffer.stock] is
 * [com.astarworks.astera.core.domain.shop.ShopStock.Limited] (Wave 5g
 * Core hardening, V19 migration).
 *
 * Composite PK on `(shop_id, offer_id)` matches the addressing key in
 * [com.astarworks.astera.core.application.port.outbound.IShopStockRepository].
 * `remaining INT NOT NULL CHECK (remaining >= 0)` rules out negative stock
 * at the DB layer — a defence-in-depth check in case the application's
 * `decrementStock` ever has a bug, the row never reaches a state the
 * domain `ShopStock.Limited` constructor would `require(remaining >= 0)`
 * reject on read.
 *
 * `ShopOffer`s declared as [com.astarworks.astera.core.domain.shop.ShopStock.Unlimited]
 * are never persisted — `currentStock` returns null for them and callers
 * short-circuit the decrement step.
 */
public object ShopStockTable : Table("shop_stock") {
    public val shopId = varchar("shop_id", 64)
    public val offerId = varchar("offer_id", 64)
    public val remaining = integer("remaining").check { it greaterEq 0 }
    override val primaryKey: PrimaryKey = PrimaryKey(shopId, offerId)
}
