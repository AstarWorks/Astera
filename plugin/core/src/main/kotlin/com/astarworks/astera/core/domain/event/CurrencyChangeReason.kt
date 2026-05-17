package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId

/**
 * Why a player's balance changed. Pure data; UI maps each variant to display
 * text via the i18n message renderer.
 */
public sealed interface CurrencyChangeReason {
    public data object Deposit : CurrencyChangeReason
    public data object Withdraw : CurrencyChangeReason
    public data class Transfer(val counterpartyId: PlayerId) : CurrencyChangeReason
    public data object MatchReward : CurrencyChangeReason
    public data object MissionReward : CurrencyChangeReason
    public data object AdminGrant : CurrencyChangeReason

    /**
     * Wave 4.5 (R3.5, ADR-0027): a shop purchase debit. Carries the addressing
     * pair `(ShopId, ShopOfferId)` so analytics / audit can correlate the
     * balance change back to the offer the player redeemed.
     */
    public data class ShopPurchase(
        val shopId: ShopId,
        val offerId: ShopOfferId,
    ) : CurrencyChangeReason
}
