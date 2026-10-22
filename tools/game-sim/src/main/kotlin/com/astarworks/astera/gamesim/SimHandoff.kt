package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared snapshot store of a simulated network - the Postgres every backend
 * reads and writes. [readLag] models a slow replica / write: after each save
 * the next `readLag` reads of that player still see the previous snapshot.
 */
public class SharedSnapshotRepository(public var readLag: Int = 0) : IPlayerInventorySnapshotRepository {

    private val current: MutableMap<PlayerId, PlayerInventorySnapshot> = ConcurrentHashMap()
    private val previous: MutableMap<PlayerId, PlayerInventorySnapshot> = ConcurrentHashMap()
    private val staleReadsLeft: MutableMap<PlayerId, Int> = ConcurrentHashMap()

    /** Reads served from the previous snapshot because of [readLag], for assertions. */
    public var staleReads: Int = 0
        private set

    override fun save(snapshot: PlayerInventorySnapshot) {
        current[snapshot.playerId]?.let { previous[snapshot.playerId] = it }
        current[snapshot.playerId] = snapshot
        if (readLag > 0 && previous.containsKey(snapshot.playerId)) staleReadsLeft[snapshot.playerId] = readLag
    }

    override fun findLatest(playerId: PlayerId): PlayerInventorySnapshot? {
        val left = staleReadsLeft[playerId] ?: 0
        if (left > 0) {
            staleReadsLeft[playerId] = left - 1
            staleReads++
            return previous[playerId]
        }
        return current[playerId]
    }

    override fun delete(playerId: PlayerId) {
        current.remove(playerId)
        previous.remove(playerId)
        staleReadsLeft.remove(playerId)
    }
}

/** A per-server hotbar standing in for the Bukkit inventory: capture / apply go through the real hand-off service. */
public class SimInventoryGateway : IPlayerInventoryGateway {

    private val hotbars: MutableMap<PlayerId, List<InventoryItemSlot?>> = ConcurrentHashMap()

    /** Puts [count] of [itemId] into the first hotbar slot of [playerId]. */
    public fun put(playerId: PlayerId, itemId: String, count: Int = 1) {
        hotbars[playerId] = listOf<InventoryItemSlot?>(InventoryItemSlot.ContentItem(itemId, count)) + List(8) { null }
    }

    /** The item ids in [playerId]'s hotbar on this server. */
    public fun items(playerId: PlayerId): List<String> =
        hotbars[playerId].orEmpty().filterIsInstance<InventoryItemSlot.ContentItem>().map { it.itemId }

    /** The player's inventory leaves with them. */
    public fun clear(playerId: PlayerId) {
        hotbars.remove(playerId)
    }

    override fun capture(playerId: PlayerId): PlayerInventorySnapshot? {
        val hotbar = hotbars[playerId] ?: List(9) { null }
        return PlayerInventorySnapshot.empty(playerId, Instant.now()).copy(hotbar = hotbar)
    }

    override fun apply(snapshot: PlayerInventorySnapshot): Boolean {
        hotbars[snapshot.playerId] = snapshot.hotbar
        return true
    }
}
