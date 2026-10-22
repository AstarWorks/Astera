package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `shop_purchases` (V64, ADR-0211): append-only purchase ledger = ownership record + player history. */
public object ShopPurchasesTable : Table("shop_purchases") {
    public val id = long("id").autoIncrement()
    public val playerId = javaUUID("player_id")
    public val shopId = varchar("shop_id", 64)
    public val offerId = varchar("offer_id", 64)
    public val itemId = varchar("item_id", 64)
    public val currency = varchar("currency", 16)
    public val amount = long("amount")
    public val purchasedAt = timestamp("purchased_at")
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
