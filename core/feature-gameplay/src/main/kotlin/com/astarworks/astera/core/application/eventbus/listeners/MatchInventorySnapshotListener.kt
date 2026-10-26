package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.port.outbound.item.IItemConsumptionRecorder
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemRemoval
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.event.PlayerLeftMatch
import com.astarworks.astera.core.domain.item.ItemId
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
    /** Takes a consumed item out again after the restore (see [IItemConsumptionRecorder]). */
    private val removal: () -> IOwnedItemRemoval? = { null },
    /** `true` while the player's inventory is not confirmed restored (hand-off pending/failed): never capture it. */
    private val restorePending: (PlayerId) -> Boolean = { false },
) : IItemConsumptionRecorder {

    private val log = LoggerFactory.getLogger(MatchInventorySnapshotListener::class.java)

    private val held: ConcurrentHashMap<MatchId, MutableMap<PlayerId, PlayerInventorySnapshot>> = ConcurrentHashMap()

    /** Consumables used up during the match, per player; taken out again after the restore. */
    private val consumed: ConcurrentHashMap<PlayerId, MutableList<ItemId>> = ConcurrentHashMap()

    override fun record(playerId: PlayerId, itemId: ItemId) {
        if (!isHeld(playerId)) return
        consumed.computeIfAbsent(playerId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(itemId)
    }

    /** Applies [snapshot], then removes what was consumed meanwhile; @return whether the player was online. */
    private fun restore(snapshot: PlayerInventorySnapshot): Boolean {
        if (gateway()?.apply(snapshot) != true) {
            consumed.remove(snapshot.playerId)
            return false
        }
        val spent = consumed.remove(snapshot.playerId).orEmpty()
        if (spent.isNotEmpty()) {
            val take = removal()
            spent.forEach { take?.removeOne(snapshot.playerId, it) }
            gateway()?.capture(snapshot.playerId)?.let(saveSnapshot::save) ?: saveSnapshot.save(snapshot)
        } else {
            saveSnapshot.save(snapshot)
        }
        return true
    }

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
        if (restorePending(playerId)) {
            log.warn("Entry snapshot of {} skipped: inventory restore not confirmed; the saved snapshot stays authoritative.", playerId)
            return
        }
        val snapshot = gateway()?.capture(playerId) ?: return
        saveSnapshot.save(snapshot)
        perMatch[playerId] = snapshot
        afterEntryCapture(playerId)
    }

    /**
     * A participant left the running match for good (forfeit, kick): their pre-match inventory goes back now if
     * they are online; offline, the persisted entry snapshot restores on their next join. Not held any more.
     */
    public fun handleLeft(event: PlayerLeftMatch) {
        val snapshot = held[event.matchId]?.remove(event.playerId) ?: return
        restore(snapshot)
    }

    public fun handleEnded(event: MatchEnded) {
        val captured = held.remove(event.matchId) ?: return
        for ((playerId, snapshot) in captured) {
            // Re-persists so the stored row reflects the restored (pre-match) inventory.
            if (!restore(snapshot)) {
                log.debug("Player {} offline at match end; persisted entry snapshot restores on next join", playerId)
            }
        }
    }
}
