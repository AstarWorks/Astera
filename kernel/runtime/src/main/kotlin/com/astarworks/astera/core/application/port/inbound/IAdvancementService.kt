package com.astarworks.astera.core.application.port.inbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementError
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementProgress
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Inbound port for cross-plugin advancement orchestration (Wave 5p, ADR-0088).
 *
 * # Why a port (not the use case directly)
 *
 * The listener that drives advancement progress lives in
 * `plugin:stats` (`TrackAdvancementProgressListener`) — it depends on combat /
 * match / items events. Stats can only depend on core, so it talks to the
 * advancement subsystem through this inbound port. Core's `ProgressAdvancementUseCase`
 * implements it; tests substitute fakes.
 *
 * # Method contract
 *
 *  - [progress] accepts a positive [increment] of the *dominant* counter
 *    (KillCount: +1 per kill; CurrencyEarned: +delta when positive; etc.).
 *    Implementations short-circuit when the player has already completed
 *    the advancement (no state change, no event).
 *  - [completed] is a convenience over `IPlayerAdvancementProgressRepository.completed`.
 *  - [progressOf] returns the current snapshot or `null` if no row exists.
 */
public interface IAdvancementService {

    /** Increment counter for `(playerId, advancementId)` by [increment] (must be > 0). */
    public suspend fun progress(
        playerId: PlayerId,
        advancementId: AdvancementId,
        increment: Int = 1,
    ): Result<AdvancementProgress, AdvancementError>

    public suspend fun completed(playerId: PlayerId): Set<AdvancementId>

    public suspend fun progressOf(playerId: PlayerId, advancementId: AdvancementId): AdvancementProgress?
}
