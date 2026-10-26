package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.PurchaseRecord
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId

/** Append-only store of completed shop purchases (ADR-0211). */
public interface IShopPurchaseRepository {

    /** Persist [record]. Called after the debit and delivery succeeded. */
    public suspend fun record(record: PurchaseRecord)

    /** How many times [playerId] bought [offerId] in [shopId]. */
    public suspend fun count(playerId: PlayerId, shopId: ShopId, offerId: ShopOfferId): Int

    /** The player's [limit] most recent purchases, newest first. */
    public suspend fun history(playerId: PlayerId, limit: Int): List<PurchaseRecord>
}
