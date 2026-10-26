package com.astarworks.astera.core.domain.shop

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/** One completed shop purchase: the ownership ledger for [PurchaseLimit.ONCE] and the player-facing history. */
public data class PurchaseRecord(
    public val playerId: PlayerId,
    public val shopId: ShopId,
    public val offerId: ShopOfferId,
    public val itemId: ItemId,
    public val price: ShopPrice,
    public val purchasedAt: Instant,
)
