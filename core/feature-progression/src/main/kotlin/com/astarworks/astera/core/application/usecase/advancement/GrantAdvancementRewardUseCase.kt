package com.astarworks.astera.core.application.usecase.advancement

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.advancement.CustomAdvancement
import com.astarworks.astera.core.domain.event.AdvancementRewardGranted
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Dispatches the reward list of a completed [CustomAdvancement] (Wave 5p,
 * ADR-0088).
 *
 * # Two-channel dispatch
 *
 *  - **[AdvancementReward.GrantCurrency]** is applied **synchronously** via
 *    [ICurrencyLedger.credit] — currency is a core-owned port, no cross-plugin
 *    indirection needed, and the payout has to land before any HUD toast that
 *    quotes the new balance. The corresponding [AdvancementRewardGranted]
 *    event is still published (for audit + analytics symmetry; subscribers
 *    treat it as "already-applied").
 *  - **[AdvancementReward.GrantItem] / GrantTitle / GrantAchievement** are
 *    fanned out as [AdvancementRewardGranted] events. The owning feature
 *    plugins (items / stats) subscribe and call their plugin-local services
 *    (`IItemDelivery`, `IAchievementRepository`, …). Core stays clean of
 *    sibling-plugin imports.
 *
 * # Ordering & retry
 *
 * Rewards are dispatched in declaration order. There is no retry: if a
 * subscriber throws, the event bus's existing error policy applies (best-effort
 * delivery to remaining subscribers; failures are logged). Phase 4 (Wave 8c+
 * outbox pattern) may revisit if at-least-once delivery becomes a requirement
 * for currency outside this flow.
 */
public class GrantAdvancementRewardUseCase(
    private val ledgerProvider: () -> ICurrencyLedger?,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    /** Apply every reward of [advancement] to [playerId]. Safe to call once per completion. */
    public suspend fun grant(playerId: PlayerId, advancement: CustomAdvancement) {
        val tick = scheduler.currentTick()
        advancement.rewards.forEachIndexed { idx, reward ->
            when (reward) {
                is AdvancementReward.GrantCurrency -> {
                    // Lazy resolution: economy plugin loads after core, so the
                    // ledger may be null on early grants. We still publish the
                    // event so subscribers can observe / log; a missing ledger
                    // simply means the credit is skipped (no-op + event).
                    ledgerProvider()?.credit(playerId, reward.currency, reward.amount)
                }
                is AdvancementReward.GrantItem,
                is AdvancementReward.GrantTitle,
                is AdvancementReward.GrantAchievement -> {
                    // Feature plugins handle these.
                }
            }
            events.publish(
                AdvancementRewardGranted(
                    playerId = playerId,
                    advancementId = advancement.id,
                    rewardIndex = idx,
                    reward = reward,
                    atTick = tick,
                ),
            )
        }
    }
}
