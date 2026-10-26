package com.astarworks.astera.core.application.port.inbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.PlayerQuestProgress
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestError
import com.astarworks.astera.core.domain.quest.QuestStageOutcome

/**
 * Inbound port for cross-plugin quest-chain orchestration (Wave 5s, ADR-0091).
 *
 * # Why a port (not the use case directly)
 *
 * Listeners that drive quest progress live in plugin layers above core (combat
 * kills increment KillCount stages, match results increment MatchesWon stages,
 * item pickups increment ItemAcquired stages — Wave 5pve / Wave 6a). Those
 * plugins can only depend on core, so they talk to the quest subsystem through
 * this inbound port. Core's `StartQuestChainUseCase` + `ProgressQuestUseCase`
 * implement it; tests substitute fakes.
 *
 * # Method contract
 *
 *  - [startChain] activates the chain's entry stage for the player. Idempotent:
 *    returns [QuestError.AlreadyStarted] if the player already has an active row,
 *    [QuestError.AlreadyCompleted] if completed.
 *  - [progressCurrent] increments the active stage's counter; transitions to
 *    the next stage (or completes the chain) when the criteria target is
 *    reached. Short-circuits to [QuestError.NotStarted] if no row exists,
 *    [QuestError.AlreadyCompleted] if the chain is finished.
 *  - [activeChains] returns every chain the player has started and not
 *    completed — the HUD / quest log uses this for the "active quests" panel.
 */
public interface IQuestService {

    /** Start [chainId] for [playerId]. Writes the entry stage to the progress row. */
    public suspend fun startChain(
        playerId: PlayerId,
        chainId: QuestChainId,
    ): Result<PlayerQuestProgress, QuestError>

    /** Increment counter for the player's current stage in [chainId] by [increment] (must be > 0). */
    public suspend fun progressCurrent(
        playerId: PlayerId,
        chainId: QuestChainId,
        increment: Int = 1,
    ): Result<QuestStageOutcome, QuestError>

    /** Snapshot of every in-progress chain row for [playerId] (excludes completed). */
    public suspend fun activeChains(playerId: PlayerId): Collection<PlayerQuestProgress>

    /** Snapshot of every chain row for [playerId] (includes completed); for quest log UI. */
    public suspend fun allChains(playerId: PlayerId): Collection<PlayerQuestProgress>
}
