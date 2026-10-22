package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Per-match inventory snapshot (WP12): **save on entry, restore on exit**.
 *
 *  - [PlayerEnteredMatch] (and [MatchStarted] for pre-populated teams): the
 *    participant's current inventory is captured and
 *    persisted through [SaveInventorySnapshotUseCase] (so a crash or a
 *    mid-match disconnect still restores the pre-match inventory on the next
 *    join) and remembered in memory.
 *  - [MatchEnded]: every remembered snapshot is written back to the live
 *    player and persisted again. Players who left meanwhile are skipped; their
 *    persisted entry snapshot is restored on their next join.
 *
 * While a player is held ([isHeld]) the quit handler must NOT overwrite the
 * persisted snapshot with the match inventory.
 *
 * Lobby kit items are filtered by the Paper serializer (`MenuKitTag`), so they
 * never enter a snapshot. At end the remembered set is used because the match aggregate may already be torn down.
 */
public class MatchInventorySnapshotListener(
    private val gateway: () -> IPlayerInventoryGateway?,
    private val saveSnapshot: SaveInventorySnapshotUseCase,
    /** Runs once per player right after the entry snapshot was taken (so anything it adds is not snapshotted). */
    private val afterEntryCapture: (PlayerId) -> Unit = {},
    private val matches: () -> IMatchRepository?,
) {

    private val log = LoggerFactory.getLogger(MatchInventorySnapshotListener::class.java)

    private val held: ConcurrentHashMap<MatchId, MutableMap<PlayerId, PlayerInventorySnapshot>> = ConcurrentHashMap()

    /** `true` while [playerId] has an entry snapshot of a running match outstanding. */
    public fun isHeld(playerId: PlayerId): Boolean = held.values.any { playerId in it }

    /**
     * A participant came back mid-match: the join restored their entry snapshot, so what the match adds on
     * top of it (the loaner kit for an unarmed player) is handed out again. No-op for players not held.
     */
    public fun rearm(playerId: PlayerId) {
        if (isHeld(playerId)) afterEntryCapture(playerId)
    }

    /** A match that already has members at start (admin paths) is captured here; later entries go through [handleEntered]. */
    public fun handleStarted(event: MatchStarted) {
        val match = matches()?.findById(event.matchId) ?: return
        match.teams.flatMap { it.members }.toSet().forEach { capture(event.matchId, it) }
    }

    /** F3-12: players join after [MatchStarted], so the snapshot is taken when they enter. */
    public fun handleEntered(event: PlayerEnteredMatch) {
        capture(event.matchId, event.playerId)
    }

    private fun capture(matchId: MatchId, playerId: PlayerId) {
        val perMatch = held.computeIfAbsent(matchId) { ConcurrentHashMap() }
        if (playerId in perMatch) return
        val snapshot = gateway()?.capture(playerId) ?: return
        saveSnapshot.save(snapshot)
        perMatch[playerId] = snapshot
        afterEntryCapture(playerId)
    }

    public fun handleEnded(event: MatchEnded) {
        val captured = held.remove(event.matchId) ?: return
        for ((playerId, snapshot) in captured) {
            if (gateway()?.apply(snapshot) != true) {
                log.debug("Player {} offline at match end; persisted entry snapshot restores on next join", playerId)
                continue
            }
            // Re-persist so the stored row reflects the restored (pre-match) inventory.
            saveSnapshot.save(snapshot)
        }
    }
}
