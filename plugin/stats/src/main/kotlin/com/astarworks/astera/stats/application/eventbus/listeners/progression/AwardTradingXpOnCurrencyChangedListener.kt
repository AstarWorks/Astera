package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase

/**
 * Awards [SkillKind.Trading] XP for marketplace-style currency churn
 * (Wave 5multi-skill, ADR-0101).
 *
 * Until Wave 12c ships a dedicated `MarketplaceListingPurchased` event, this
 * listener uses [CurrencyChanged] as a proxy:
 *  - [CurrencyChangeReason.ShopPurchase]: positive contribution to Trading XP
 *    (the buyer is exchanging currency for goods, which is the "Trading"
 *    activity per ADR-0101 mapping).
 *
 * Other reasons (Deposit / Withdraw / MatchReward / MissionReward /
 * AdminGrant / Transfer) are deliberately ignored — they represent neither
 * "trading skill expressed" nor a marketplace transaction.
 *
 * XP scaling: `ceil(|delta| / XP_DIVISOR)`. Default tuning of 100 means a
 * 1000-currency shop purchase yields 10 Trading XP.
 */
public class AwardTradingXpOnCurrencyChangedListener(
    private val awardXp: AwardSkillXpUseCase,
    private val xpDivisor: Long = TRADING_XP_DIVISOR,
) {
    init {
        require(xpDivisor >= 1L) { "xpDivisor must be >= 1, got $xpDivisor" }
    }

    public fun handle(event: CurrencyChanged) {
        if (event.reason !is CurrencyChangeReason.ShopPurchase) return
        val magnitude = Math.abs(event.delta)
        if (magnitude <= 0L) return
        val xp = (magnitude + xpDivisor - 1L) / xpDivisor // ceil-div
        if (xp <= 0L) return
        awardXp.execute(event.playerId, SkillKind.Trading, xp)
    }

    public companion object {
        public const val TRADING_XP_DIVISOR: Long = 100L
    }
}
