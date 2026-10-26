package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot

/** Durable tutorial cursor per player. Completed rows retain the graduation latch.
 * Paper supplies atomic local files; Persistence overrides with Postgres for network backends.
 */
public interface IPlayerTutorialStateRepository {

    public suspend fun find(playerId: PlayerId): TutorialRunnerSnapshot?

    public suspend fun save(playerId: PlayerId, snapshot: TutorialRunnerSnapshot)

    public suspend fun clear(playerId: PlayerId)

    /** Snapshot including paused and completed cursors. */
    public suspend fun all(): Map<PlayerId, TutorialRunnerSnapshot>
}
