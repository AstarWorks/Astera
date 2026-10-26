package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.PurchaseRecord
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory [IShopPurchaseRepository]; the Postgres overlay (priority 100) replaces it. */
public class InMemoryShopPurchaseRepository : IShopPurchaseRepository {
    private val records = CopyOnWriteArrayList<PurchaseRecord>()

    override suspend fun record(record: PurchaseRecord) {
        records += record
    }

    override suspend fun count(playerId: PlayerId, shopId: ShopId, offerId: ShopOfferId): Int =
        records.count { it.playerId == playerId && it.shopId == shopId && it.offerId == offerId }

    override suspend fun history(playerId: PlayerId, limit: Int): List<PurchaseRecord> =
        records.filter { it.playerId == playerId }.sortedByDescending { it.purchasedAt }.take(limit)
}
