package com.astarworks.astera.core.application.service.tutorial

import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IPlayerTutorialStateRepository] (Wave 5p, ADR-0088).
 *
 * Registered at `priority=0` in `AsteraCorePlugin`; preserves Wave 5e's
 * volatile-state UX. A Postgres overlay can land in Phase 4 without
 * touching the use case.
 */
public class InMemoryPlayerTutorialStateRepository : IPlayerTutorialStateRepository {

    private val store: ConcurrentHashMap<PlayerId, TutorialRunnerSnapshot> = ConcurrentHashMap()

    override fun find(playerId: PlayerId): TutorialRunnerSnapshot? = store[playerId]

    override fun save(playerId: PlayerId, snapshot: TutorialRunnerSnapshot) {
        store[playerId] = snapshot
    }

    override fun clear(playerId: PlayerId) {
        store.remove(playerId)
    }

    override fun all(): Map<PlayerId, TutorialRunnerSnapshot> = store.toMap()
}
