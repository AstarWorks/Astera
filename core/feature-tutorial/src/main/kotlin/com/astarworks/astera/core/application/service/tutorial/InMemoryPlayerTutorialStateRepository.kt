package com.astarworks.astera.core.application.service.tutorial

import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import java.util.concurrent.ConcurrentHashMap

/** Simulator / unit-test store. Paper uses durable files; network servers use Postgres. */
public class InMemoryPlayerTutorialStateRepository : IPlayerTutorialStateRepository {

    private val store: ConcurrentHashMap<PlayerId, TutorialRunnerSnapshot> = ConcurrentHashMap()

    override suspend fun find(playerId: PlayerId): TutorialRunnerSnapshot? = store[playerId]

    override suspend fun save(playerId: PlayerId, snapshot: TutorialRunnerSnapshot) {
        store[playerId] = snapshot
    }

    override suspend fun clear(playerId: PlayerId) {
        store.remove(playerId)
    }

    override suspend fun all(): Map<PlayerId, TutorialRunnerSnapshot> = store.toMap()
}
