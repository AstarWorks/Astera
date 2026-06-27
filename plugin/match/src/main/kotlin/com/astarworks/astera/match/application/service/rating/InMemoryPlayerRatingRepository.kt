package com.astarworks.astera.match.application.service.rating

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository
import com.astarworks.astera.match.domain.rating.Rating

/**
 * In-memory [IPlayerRatingRepository] (Wave 7b, ADR-0037).
 *
 * Default registration (priority 0) in `plugin:match`; the Postgres overlay in
 * `plugin:persistence` (priority 100, V47 `player_ratings`) overrides it via
 * the `ServiceRegistry`. No persistence here — ratings reset on restart, which
 * is fine for the Phase 1 PoC and for tests.
 */
public class InMemoryPlayerRatingRepository : IPlayerRatingRepository {

    private val ratings: MutableMap<PlayerId, Rating> = mutableMapOf()

    override fun find(playerId: PlayerId): Rating? = ratings[playerId]

    override fun upsert(playerId: PlayerId, rating: Rating) {
        ratings[playerId] = rating
    }

    override fun all(): Map<PlayerId, Rating> = ratings.toMap()
}
