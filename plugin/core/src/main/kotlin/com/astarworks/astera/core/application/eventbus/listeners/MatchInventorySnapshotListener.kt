package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Per-match inventory snapshot (WP12): **save on entry, restore on exit**.
 *
 *  - [MatchStarted]: every participant's current inventory is captured and
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
 * Participants come from [IMatchRepository] at start; at end the remembered
 * set is used because the match aggregate may already be torn down.
 */
public class MatchInventorySnapshotListener(
    private val gateway: () -> IPlayerInventoryGateway?,
    private val saveSnapshot: SaveInventorySnapshotUseCase,
    private val matches: () -> IMatchRepository?,
) {

    private val log = LoggerFactory.getLogger(MatchInventorySnapshotListener::class.java)

    private val held: ConcurrentHashMap<MatchId, Map<PlayerId, PlayerInventorySnapshot>> = ConcurrentHashMap()

    /** `true` while [playerId] has an entry snapshot of a running match outstanding. */
    public fun isHeld(playerId: PlayerId): Boolean = held.values.any { playerId in it }

    public fun handleStarted(event: MatchStarted) {
        val match = matches()?.findById(event.matchId) ?: return
        val participants = match.teams.flatMap { it.members }.toSet()
        val captured = LinkedHashMap<PlayerId, PlayerInventorySnapshot>()
        for (playerId in participants) {
            val snapshot = gateway()?.capture(playerId) ?: continue
            saveSnapshot.save(snapshot)
            captured[playerId] = snapshot
        }
        held[event.matchId] = captured
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
