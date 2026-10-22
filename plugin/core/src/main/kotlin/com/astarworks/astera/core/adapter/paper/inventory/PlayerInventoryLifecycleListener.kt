package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Bukkit `PlayerJoinEvent` / `PlayerQuitEvent` driver for
 * [IPlayerInventorySnapshotRepository] (Wave 6b-3, ADR-0158).
 *
 * Closes the Phase 1 PoC smoke-test path #3 (re-login restores loadout)
 * the Wave 5e wiring promised but could not exercise — without a Paper
 * adapter the V10 row stayed at [com.astarworks.astera.core.domain.player.PlayerInventorySnapshot.empty].
 *
 * # Lifecycle
 *
 *  - **onPlayerJoin**: look up the latest snapshot, deserialize into the
 *    player's live inventory via [PaperInventorySnapshotSerializer]. While
 *    the restore runs the player's [PlayerSession] is forced to
 *    [SessionState.Loading] so [InventoryActionLockEnforcer] suppresses
 *    click / pickup / interact and the player cannot pre-empt the restore
 *    with a partial action. State transitions back to [SessionState.Lobby]
 *    after the restore completes (success or failure — the lock is
 *    transient by design).
 *  - **onPlayerQuit**: serialize the current inventory into the V10 row
 *    so the next login (possibly on a sibling Paper instance via Phase 2
 *    server-transfer) sees the same items. Latest-wins; no history.
 *    Skipped while [isHeld] says the player is inside a running match: the
 *    persisted row is then the pre-match entry snapshot
 *    ([com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener])
 *    and must survive so the next join restores the pre-match inventory.
 *
 * # Error isolation (per-player)
 *
 * The full restore / save pipeline (snapshot lookup, serializer call,
 * repository write) is wrapped per-player in try/catch — one corrupt row
 * must not crash the join handler for everyone else queued behind it.
 * Same posture as `BukkitEventAdapter.safePublish` (ADR-0152 §A) and
 * `PaperPlayerFoodGateway`'s event guards.
 *
 * # Registration
 *
 * Construct in the Paper bindings and call [start] with the `JavaPlugin`
 * instance to register the Bukkit listener.
 */
public class PlayerInventoryLifecycleListener(
    private val server: Server,
    private val snapshotRepo: IPlayerInventorySnapshotRepository,
    private val serializer: PaperInventorySnapshotSerializer,
    private val sessions: IPlayerSessionRepository,
    private val scheduler: IScheduler,
    private val isHeld: (PlayerId) -> Boolean,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Register the Bukkit listener. */
    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * Restore the player's inventory from the V10 snapshot row, if one
     * exists. Marks [SessionState.Loading] for the duration so
     * [InventoryActionLockEnforcer] suppresses click / pickup / interact;
     * transitions back to [SessionState.Lobby] when done.
     *
     * Runs at [EventPriority.NORMAL] so other gameplay plugins still see
     * the join event before the inventory is rewritten — this matters for
     * Phase 1 PoC ordering with permission / scoreboard adapters.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val playerId = PlayerId(player.uniqueId)
        markLoading(playerId)
        try {
            val snapshot = snapshotRepo.findLatest(playerId)
            if (snapshot == null) {
                if (log.isDebugEnabled) {
                    log.debug(
                        "PlayerInventoryLifecycleListener.onPlayerJoin: no snapshot for {}; leaving inventory untouched",
                        playerId,
                    )
                }
                return
            }
            serializer.deserialize(snapshot, player)
            log.info(
                "PlayerInventoryLifecycleListener.onPlayerJoin: restored snapshot for {} (version={} savedAt={})",
                playerId,
                snapshot.version,
                snapshot.savedAt,
            )
        } catch (t: Throwable) {
            log.warn(
                "PlayerInventoryLifecycleListener.onPlayerJoin: restore failed for {}; player joins with default inventory",
                playerId,
                t,
            )
        } finally {
            markLobby(playerId)
        }
    }

    /**
     * Persist the player's current inventory on quit. Runs at
     * [EventPriority.MONITOR] so other plugins finish their teardown
     * (loadout-on-quit handlers, inventory cleanup) before Astera takes
     * the final snapshot.
     *
     * `ignoreCancelled = false` — `PlayerQuitEvent` is not cancellable.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val playerId = PlayerId(player.uniqueId)
        if (isHeld(playerId)) {
            log.debug("onPlayerQuit: {} is inside a match; keeping the pre-match entry snapshot", playerId)
            return
        }
        try {
            val snapshot = serializer.serialize(player)
            snapshotRepo.save(snapshot)
            if (log.isDebugEnabled) {
                log.debug(
                    "PlayerInventoryLifecycleListener.onPlayerQuit: saved snapshot for {} (version={})",
                    playerId,
                    snapshot.version,
                )
            }
        } catch (t: Throwable) {
            log.warn(
                "PlayerInventoryLifecycleListener.onPlayerQuit: save failed for {}; previous snapshot retained",
                playerId,
                t,
            )
        }
    }

    /**
     * Force the player's session into [SessionState.Loading], preserving
     * any current [PlayerSession.currentMatch] binding. If the player has
     * no tracked session yet (first join of the server lifetime) a fresh
     * Loading-state session is created.
     */
    private fun markLoading(playerId: PlayerId) {
        val current = sessions.find(playerId)
        val next = current?.copy(state = SessionState.Loading)
            ?: PlayerSession(playerId = playerId, state = SessionState.Loading, currentMatch = null)
        sessions.upsert(next)
    }

    /**
     * Transition the player out of [SessionState.Loading] into
     * [SessionState.Lobby] (the canonical post-join resting state). Match
     * lifecycle adapters move the session to [SessionState.InMatch] etc.
     * once the player joins a match aggregate.
     */
    private fun markLobby(playerId: PlayerId) {
        val current = sessions.find(playerId) ?: return
        if (current.state == SessionState.Loading) {
            sessions.upsert(current.copy(state = SessionState.Lobby, currentMatch = null))
        }
    }

    /**
     * Currently unused — kept here so the next sub-wave that adds a
     * mid-restore async hook (e.g., Postgres fetch off-main) can switch
     * the restore to scheduler-driven without touching the public method
     * signatures.
     */
    @Suppress("unused")
    private val schedulerRef: IScheduler = scheduler
}
