package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.PurchaseRecord
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.persistence.table.ShopPurchasesTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Postgres-backed [IShopPurchaseRepository] (V64, ADR-0211); overlays the in-memory default at priority 100. */
public class PostgresShopPurchaseRepository(private val db: Database) : IShopPurchaseRepository {

    override fun record(record: PurchaseRecord): Unit = transaction(db) {
        ShopPurchasesTable.insert {
            it[playerId] = record.playerId.uuid
            it[shopId] = record.shopId.value
            it[offerId] = record.offerId.value
            it[itemId] = record.itemId.value
            it[currency] = record.price.currency.name
            it[amount] = record.price.amount
            it[purchasedAt] = record.purchasedAt
        }
        Unit
    }

    override fun count(playerId: PlayerId, shopId: ShopId, offerId: ShopOfferId): Int = transaction(db) {
        ShopPurchasesTable.selectAll()
            .where {
                (ShopPurchasesTable.playerId eq playerId.uuid) and
                    (ShopPurchasesTable.shopId eq shopId.value) and
                    (ShopPurchasesTable.offerId eq offerId.value)
            }
            .count()
            .toInt()
    }

    override fun history(playerId: PlayerId, limit: Int): List<PurchaseRecord> = transaction(db) {
        ShopPurchasesTable.selectAll()
            .where { ShopPurchasesTable.playerId eq playerId.uuid }
            .orderBy(ShopPurchasesTable.purchasedAt to SortOrder.DESC, ShopPurchasesTable.id to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                PurchaseRecord(
                    playerId = playerId,
                    shopId = ShopId(row[ShopPurchasesTable.shopId]),
                    offerId = ShopOfferId(row[ShopPurchasesTable.offerId]),
                    itemId = ItemId(row[ShopPurchasesTable.itemId]),
                    price = ShopPrice(Currency.valueOf(row[ShopPurchasesTable.currency]), row[ShopPurchasesTable.amount]),
                    purchasedAt = row[ShopPurchasesTable.purchasedAt],
                )
            }
    }
}
