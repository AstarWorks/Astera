package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.usecase.inventory.RestoreInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import org.slf4j.LoggerFactory

/**
 * On [PlayerJoinedServer], trigger [RestoreInventorySnapshotUseCase] (Wave 5e
 * / RTM issue #5).
 *
 * Wave 6b's `BukkitEventAdapter.onPlayerJoin` publishes [PlayerJoinedServer]
 * after the Bukkit `PlayerJoinEvent`; today there is no such publisher, so
 * this listener is wired-but-dormant. Driving it from a domain event (rather
 * than the adapter-level `IMcEvent.PlayerJoined`) keeps subscribers free of
 * Bukkit imports — the same ``application/`` Konsist rule that bans Paper from
 * the use cases applies here.
 *
 * Restore failures are logged at WARN and swallowed: a player joining the
 * lobby with a broken snapshot row should still complete the join (the
 * `RestoreFailed` path already unwinds the `Loading` state before bailing).
 */
public class RestoreInventoryOnPlayerJoinedListener(
    private val restoreSnapshot: RestoreInventorySnapshotUseCase,
) {

    private val log = LoggerFactory.getLogger(RestoreInventoryOnPlayerJoinedListener::class.java)

    public fun handle(event: PlayerJoinedServer) {
        val result = restoreSnapshot.restore(event.playerId)
        result.onFailure { err ->
            log.warn("Inventory restore failed for {}: {}", event.playerId, err)
        }
    }
}
