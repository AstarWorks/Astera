package com.astarworks.astera.match.application.port.outbound.rating

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.rating.Rating

/**
 * Outbound port for per-player global Elo rating (Wave 7b, ADR-0037).
 *
 * Used by ranked matchmaking (`InMemoryMatchmaker.ratingLookup`) and by
 * `UpdateRatingOnMatchEndedUseCase`. In-memory default (priority 0) in
 * `plugin:match`; Postgres overlay (priority 100, V47 `player_ratings`) in
 * `plugin:persistence`.
 */
public interface IPlayerRatingRepository {

    /** Stored rating for [playerId], or null if the player has no record. */
    public fun find(playerId: PlayerId): Rating?

    /** [find] with a [Rating.DEFAULT] fallback — never null. */
    public fun ratingOf(playerId: PlayerId): Rating = find(playerId) ?: Rating.DEFAULT

    /** Insert or replace [playerId]'s rating. */
    public fun upsert(playerId: PlayerId, rating: Rating)

    /** Snapshot of every recorded rating. */
    public fun all(): Map<PlayerId, Rating>
}
