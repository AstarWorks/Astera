package com.astarworks.astera.core.domain.advancement

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Reward granted on advancement completion (Wave 5p, ADR-0088).
 *
 * # Why ids only, not domain objects
 *
 * Each variant carries **identifier-only** payloads (`ItemId`, `AchievementId`,
 * `TitleId`, `Currency` + amount). The actual side-effect (deposit currency,
 * deliver item, unlock achievement) is dispatched in
 * [com.astarworks.astera.core.application.usecase.advancement.GrantAdvancementRewardUseCase]
 * which lives in core's application layer but delegates to feature-plugin
 * services (`ICurrencyLedger`, `IItemDelivery` overlays, `IAchievementRepository`)
 * looked up via `ServiceRegistry`. Core stays unaware of how the grant
 * actually lands.
 *
 * Mirrors [com.astarworks.astera.core.domain.norma.NormaReward]'s shape (also
 * id-based; different sealed hierarchy because Norma rewards are
 * match-bounded and only ever transfer currency; advancement rewards are
 * cross-session).
 */
public sealed interface AdvancementReward {

    /** Deposit [amount] units of [currency] to the player's wallet. */
    public data class GrantCurrency(
        public val currency: Currency,
        public val amount: Long,
    ) : AdvancementReward {
        init {
            require(amount > 0) { "AdvancementReward.GrantCurrency.amount must be > 0, got $amount" }
        }
    }

    /** Materialise [count] of [itemId] into the player's inventory (via `IItemDelivery`). */
    public data class GrantItem(
        public val itemId: ItemId,
        public val count: Int,
    ) : AdvancementReward {
        init {
            require(count > 0) { "AdvancementReward.GrantItem.count must be > 0, got $count" }
        }
    }

    /** Unlock [titleId] on the player (`IPlayerTitleRepository` in stats). */
    public data class GrantTitle(public val titleId: TitleId) : AdvancementReward

    /** Unlock [achievementId] (separate vocabulary from advancement; see ADR-0088). */
    public data class GrantAchievement(public val achievementId: AchievementId) : AdvancementReward
}
