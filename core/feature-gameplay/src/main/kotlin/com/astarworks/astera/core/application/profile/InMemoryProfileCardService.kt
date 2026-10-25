package com.astarworks.astera.core.application.profile

import com.astarworks.astera.core.application.port.outbound.IProfileCardService
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.profile.PublicProfileCard
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IProfileCardService] (Wave 5flex / ADR-0102 surface #3).
 *
 * Concurrent map so the recomputation use case (potentially fired from
 * a scheduled task) can safely race with REST read traffic. Postgres
 * overlay replaces this at priority 100 once `:plugin:persistence`
 * loads.
 */
public class InMemoryProfileCardService : IProfileCardService {

    private val store = ConcurrentHashMap<PlayerId, PublicProfileCard>()

    override fun find(playerId: PlayerId): PublicProfileCard? = store[playerId]

    override fun save(card: PublicProfileCard) {
        store[card.playerId] = card
    }

    override fun delete(playerId: PlayerId) {
        store.remove(playerId)
    }

    override fun all(): List<PublicProfileCard> = store.values.toList()
}
