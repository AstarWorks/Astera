package com.astarworks.astera.core.application.usecase.quest

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.event.AdvancementRewardGranted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestStageId

/**
 * Dispatches the reward list of a completed quest stage (Wave 5s, ADR-0091).
 *
 * # Why a quest-specific use case (vs. reusing the advancement variant)
 *
 * [com.astarworks.astera.core.application.usecase.advancement.GrantAdvancementRewardUseCase]
 * takes a `CustomAdvancement` and publishes [AdvancementRewardGranted] keyed
 * by the advancement's id. Quest stages don't have an `AdvancementId` — they
 * have `(QuestChainId, QuestStageId)`. Re-shaping the Wave 5p use case to
 * accept either would muddy its contract; cleaner to keep two thin orchestrators
 * that share the same dispatch policy.
 *
 * # Dispatch contract (shared with advancement)
 *
 *  - **[AdvancementReward.GrantCurrency]** is applied synchronously via
 *    [ICurrencyLedger.credit].
 *  - **GrantItem / GrantTitle / GrantAchievement** are fanned out as
 *    [AdvancementRewardGranted] events for feature-plugin subscribers.
 *  - Synthetic `AdvancementId` of the form `quest:<chainId>:<stageId>` is used
 *    in the event payload so subscribers can disambiguate quest-driven grants
 *    from true advancement grants without changing the event schema.
 *  - Rewards are dispatched in declaration order; no retry on subscriber
 *    failure (matches Wave 5p policy).
 */
public class GrantQuestRewardUseCase(
    private val ledgerProvider: () -> ICurrencyLedger?,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    /** Apply every reward of a quest stage to [playerId]. Safe to call once per completion. */
    public suspend fun grant(
        playerId: PlayerId,
        chainId: QuestChainId,
        stageId: QuestStageId,
        rewards: List<AdvancementReward>,
    ) {
        if (rewards.isEmpty()) return

        val tick = scheduler.currentTick()
        val syntheticId = AdvancementId(syntheticAdvancementId(chainId, stageId))
        rewards.forEachIndexed { idx, reward ->
            when (reward) {
                is AdvancementReward.GrantCurrency -> {
                    ledgerProvider()?.credit(playerId, reward.currency, reward.amount)
                }
                is AdvancementReward.GrantItem,
                is AdvancementReward.GrantTitle,
                is AdvancementReward.GrantAchievement -> {
                    // Feature plugins handle these via the event below.
                }
            }
            events.publish(
                AdvancementRewardGranted(
                    playerId = playerId,
                    advancementId = syntheticId,
                    rewardIndex = idx,
                    reward = reward,
                    atTick = tick,
                ),
            )
        }
    }

    private companion object {
        /**
         * Synthetic id used in [AdvancementRewardGranted.advancementId] so
         * subscribers can distinguish quest-driven from advancement-driven
         * grants. Format: `quest-<chainId>-<stageId>` (lower-kebab-case
         * preserved end-to-end so [AdvancementId]'s validation passes).
         */
        fun syntheticAdvancementId(chainId: QuestChainId, stageId: QuestStageId): String =
            "quest-${chainId.value}-${stageId.value}"
    }
}
