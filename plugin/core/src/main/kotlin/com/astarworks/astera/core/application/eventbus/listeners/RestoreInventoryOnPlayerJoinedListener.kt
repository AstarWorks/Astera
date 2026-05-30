package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.error.IErrorReporter
import com.astarworks.astera.core.application.usecase.inventory.RestoreInventorySnapshotUseCase
import com.astarworks.astera.core.domain.error.ErrorSource
import com.astarworks.astera.core.domain.error.ServerError
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.player.SessionState
import org.slf4j.LoggerFactory
import java.time.Instant

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
 * # Double-join idempotency (Wave E 5admin-ops, ADR-0122 §C)
 *
 * Two `PlayerJoinedServer` events for the same player within a single
 * session lifetime indicate a duplicate dispatch (cross-server race, retry,
 * test bug). When the existing session's state is not `Disconnected`, the
 * inventory restore is skipped — re-running [RestoreInventorySnapshotUseCase]
 * for a live player would overwrite their current inventory with the last
 * persisted snapshot. A `Disconnected` session (clean quit pending
 * reconnect) is the expected reconnect path; the restore must still run.
 *
 * # Failure mode
 *
 * Restore failures are logged at WARN and swallowed: a player joining the
 * lobby with a broken snapshot row should still complete the join (the
 * `RestoreFailed` path already unwinds the `Loading` state before bailing).
 */
public class RestoreInventoryOnPlayerJoinedListener(
    private val restoreSnapshot: RestoreInventorySnapshotUseCase,
    private val sessionRepo: IPlayerSessionRepository,
    private val errorReporter: IErrorReporter? = null,
    private val clock: () -> Instant = { Instant.now() },
) {

    private val log = LoggerFactory.getLogger(RestoreInventoryOnPlayerJoinedListener::class.java)

    public fun handle(event: PlayerJoinedServer) {
        val existing = sessionRepo.find(event.playerId)
        if (existing != null && existing.state !is SessionState.Disconnected) {
            log.warn(
                "Duplicate PlayerJoinedServer for {} (state={}); skipping inventory restore.",
                event.playerId,
                existing.state::class.simpleName,
            )
            return
        }

        val result = restoreSnapshot.restore(event.playerId)
        result.onFailure { err ->
            log.warn("Inventory restore failed for {}: {}", event.playerId, err)
            // Wave L 5hardening-complete (ADR-0129): structured fan-out for
            // session-restore failure (a "match state corruption" signal —
            // the player's snapshot row is broken or the repo NPE'd).
            // Critical because the join completes with an empty inventory
            // and the operator must inspect the audit row to recover.
            errorReporter?.report(
                ServerError.Critical(
                    occurredAt = clock(),
                    summary = "Inventory restore failed on PlayerJoinedServer (player=${event.playerId})",
                    source = ErrorSource.UseCase(name = "RestoreInventorySnapshotUseCase"),
                    causeShortDescription = err::class.simpleName,
                ),
            )
        }
    }
}
