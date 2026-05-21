package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.PlayerForfeited

/**
 * Apply a forfeit: drop the player from the match via [RemoveFromMatchUseCase]
 * with [RemoveReason.Forfeit], then publish [PlayerForfeited] so Stats /
 * Economy can record a loss-by-forfeit (Wave 5h).
 *
 * Used by `DisconnectDuringMatchListener` to short-circuit the reconnect
 * window during [com.astarworks.astera.core.domain.match.MatchPhase.SUDDEN_DEATH]
 * (ADR-0079 §6), and by `/astera forfeit` commands once the Wave 6 command
 * surface lands.
 *
 * The remove-side failure short-circuits the publish (no forfeit event if the
 * remove itself fails); the caller's [Result] preserves the underlying
 * [RemoveError] so the listener can decide to log + drop or retry.
 */
public class ApplyForfeitUseCase(
    private val removeFromMatch: RemoveFromMatchUseCase,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun apply(playerId: PlayerId, matchId: MatchId): Result<Unit, ForfeitError> {
        val removeResult = removeFromMatch.remove(
            playerId = playerId,
            matchId = matchId,
            reason = RemoveReason.Forfeit,
        )

        return when (removeResult) {
            is Result.Success -> {
                eventBus.publish(
                    PlayerForfeited(
                        playerId = playerId,
                        matchId = matchId,
                        atTick = scheduler.currentTick(),
                    ),
                )
                Result.success(Unit)
            }

            is Result.Failure -> Result.failure(ForfeitError.RemoveFailed(removeResult.error))
        }
    }
}

/** Expected failure modes of [ApplyForfeitUseCase.apply]. */
public sealed class ForfeitError {
    public abstract val messageKey: MessageKey

    public data class RemoveFailed(val cause: RemoveError) : ForfeitError() {
        override val messageKey: MessageKey = cause.messageKey
    }
}
