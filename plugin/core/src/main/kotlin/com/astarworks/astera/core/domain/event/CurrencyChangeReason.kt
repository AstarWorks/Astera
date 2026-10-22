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

    /** Daily login bonus (ADR-0210). */
    public data object LoginBonus : CurrencyChangeReason
    public data object AdminGrant : CurrencyChangeReason

    /**
     * Currency credited as part of the onboarding loop closing reward
     * (Wave K 5integration-wire, ADR-0128). Fired by
     * `GrantRewardOnTutorialCompletedListener` (`:plugin:economy`) on
     * the `TutorialCompleted` domain event.
     */
    public data object TutorialReward : CurrencyChangeReason

    /**
     * Wave 4.5 (R3.5, ADR-0027): a shop purchase debit. Carries the addressing
     * pair `(ShopId, ShopOfferId)` so analytics / audit can correlate the
     * balance change back to the offer the player redeemed.
     */
    public data class ShopPurchase(
        val shopId: ShopId,
        val offerId: ShopOfferId,
    ) : CurrencyChangeReason

    /**
     * Wave 5bet (ADR-0103): a 1v1 betting flow currency movement. One variant
     * covers all three movements (escrow debit / winner payout / refund) —
     * the [kind] is carried separately so analytics can split the three
     * series cleanly.
     *
     * [bettingMatchUuid] is the raw UUID of the underlying
     * `com.astarworks.astera.economy.domain.betting.BettingMatchId` —
     * Core does NOT depend on the Economy plugin per ADR-0022.
     *
     * [kind] is one of `"escrow_debit"` / `"winner_payout"` / `"refund"`.
     */
    public data class Betting(
        val bettingMatchUuid: java.util.UUID,
        val kind: String,
    ) : CurrencyChangeReason
}
