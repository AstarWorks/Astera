package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState

/**
 * In-memory [IPlayerTutorialStateRepository] for use case tests (Wave 5h).
 *
 * Mirrors [FakePlayerRepository] in shape. Single-threaded by contract; the
 * Phase 2 `PostgresPlayerTutorialStateRepository` supersedes this for real
 * deployments via the priority-100 overlay.
 */
public class FakePlayerTutorialStateRepository : IPlayerTutorialStateRepository {

    private val store: MutableMap<PlayerId, PlayerTutorialState> = linkedMapOf()

    override fun find(playerId: PlayerId): PlayerTutorialState? = store[playerId]

    override fun upsert(state: PlayerTutorialState) {
        store[state.playerId] = state
    }

    override fun delete(playerId: PlayerId) {
        store.remove(playerId)
    }
}
