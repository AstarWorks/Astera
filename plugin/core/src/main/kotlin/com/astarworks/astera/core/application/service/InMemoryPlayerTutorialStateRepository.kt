package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState

/**
 * In-memory [IPlayerTutorialStateRepository] (Wave 5h).
 *
 * Backed by a single [MutableMap] — single-threaded by contract. Astera
 * dispatches use cases on the Paper main thread (silly-knuth §6) so no
 * concurrency primitives are needed; if a future wave introduces async
 * tutorial flows the map can be swapped for a `ConcurrentHashMap` without
 * changing the public surface.
 *
 * Priority-0 default. The persistence plugin's
 * `PostgresPlayerTutorialStateRepository` registers at priority 100 and
 * overlays this impl on real deployments per ADR-0068.
 */
public class InMemoryPlayerTutorialStateRepository : IPlayerTutorialStateRepository {

    private val store: MutableMap<PlayerId, PlayerTutorialState> = linkedMapOf()

    override fun find(playerId: PlayerId): PlayerTutorialState? = store[playerId]

    override fun upsert(state: PlayerTutorialState) {
        store[state.playerId] = state
    }

    override fun delete(playerId: PlayerId) {
        store.remove(playerId)
    }
}
