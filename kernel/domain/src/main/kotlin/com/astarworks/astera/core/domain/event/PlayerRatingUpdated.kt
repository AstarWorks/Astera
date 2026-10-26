package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A player's competitive Elo rating changed as the result of a finished match
 * (Wave 7b, ADR-0037).
 *
 * Published by Match (`UpdateRatingOnMatchEndedUseCase`) once per participant
 * after a [MatchEnded]; subscribed by Stats to materialise the per-season
 * leaderboard. Rating is carried as a plain [Int] so Core stays free of
 * Match's `Rating` value class (ADR-0022 vendor-neutral event boundary).
 *
 * @property won whether the player was on the winning team — lets the
 *   leaderboard increment W/L without re-reading the match roster.
 */
public data class PlayerRatingUpdated(
    val playerId: PlayerId,
    val oldRating: Int,
    val newRating: Int,
    val matchId: MatchId,
    val won: Boolean,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
