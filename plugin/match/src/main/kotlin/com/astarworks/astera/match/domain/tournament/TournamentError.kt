package com.astarworks.astera.match.domain.tournament

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Expected failure modes of the tournament use cases (Wave 7b, ADR-0037).
 *
 * Each variant carries a [messageKey] so adapters can render a localised
 * message without `when`-ing over the type, mirroring `StartMatchError`. These
 * are *values*, not bugs — see [com.astarworks.astera.core.domain.Result].
 */
public sealed interface TournamentError {

    public val messageKey: MessageKey

    /** Fewer than two distinct seeds were supplied to create a bracket. */
    public data class TooFewSeeds(val count: Int) : TournamentError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.tournament.error.too-few-seeds")
    }

    /** The same player appears more than once in the seed list. */
    public data class DuplicateSeed(val playerId: PlayerId) : TournamentError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.tournament.error.duplicate-seed")
    }

    /** No tournament exists with the given id. */
    public data class NotFound(val id: TournamentId) : TournamentError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.tournament.error.not-found")
    }

    /** A result was reported against an already-decided tournament. */
    public data class AlreadyConcluded(val id: TournamentId) : TournamentError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.tournament.error.already-concluded")
    }

    /**
     * The reported player has no undecided match in the current round — they
     * have been eliminated, have not yet advanced, or are not a participant.
     */
    public data class PlayerNotInCurrentRound(
        val id: TournamentId,
        val playerId: PlayerId,
    ) : TournamentError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.tournament.error.player-not-in-current-round")
    }

    /**
     * The reported player's match in the current round has already been
     * decided; report the other pending matches before this round advances.
     */
    public data class AlreadyDecided(
        val id: TournamentId,
        val playerId: PlayerId,
    ) : TournamentError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.tournament.error.already-decided")
    }
}
