package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId

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
}
