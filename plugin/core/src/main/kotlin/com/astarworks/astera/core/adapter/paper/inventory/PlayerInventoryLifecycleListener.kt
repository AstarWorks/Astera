package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.event.MatchEnded
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
 * Bukkit `PlayerJoinEvent` / `PlayerQuitEvent` + [MatchEnded] driver for
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
 *  - **MatchEnded (domain event)**: snapshot every currently online
 *    player. The match plugin's `SaveInventoryOnMatchEndedListener`
 *    (Wave 5e) writes [com.astarworks.astera.core.domain.player.PlayerInventorySnapshot.empty]
 *    placeholders for participants — Core overwrites those with the real
 *    Paper-side captures before the player can quit. ADR-0022 forbids
 *    Core from importing the match plugin, so this Core-side listener
 *    is the only place a Paper-aware MatchEnded handler can live.
 *    Same latest-wins semantics; per-player failures log WARN and skip.
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
 * Construct in `AsteraCorePlugin.onEnable` and call [start] with the
 * `JavaPlugin` instance to register the Bukkit listener and subscribe
 * the MatchEnded handler on the domain event bus. The [MatchEnded]
 * subscription is held internally and cancelled by [stop] for `onDisable`.
 */
public class PlayerInventoryLifecycleListener(
    private val server: Server,
    private val snapshotRepo: IPlayerInventorySnapshotRepository,
    private val serializer: PaperInventorySnapshotSerializer,
    private val sessions: IPlayerSessionRepository,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    private var matchEndedSubscription: IDomainEventBus.Subscription? = null

    /**
     * Register Bukkit listeners + subscribe to [MatchEnded] on the domain
     * event bus. Called by `AsteraCorePlugin.onEnable` after construction.
     */
    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
        matchEndedSubscription = eventBus.subscribe(MatchEnded::class) { event ->
            handleMatchEnded(event)
        }
    }

    /**
     * Cancel the [MatchEnded] subscription. Bukkit listener teardown is
     * handled by Paper's `HandlerList.unregisterAll(plugin)` during the
     * normal plugin disable path, so it is not repeated here.
     */
    public fun stop() {
        matchEndedSubscription?.cancel()
        matchEndedSubscription = null
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
     * Snapshot every currently online player at [MatchEnded]. Overwrites
     * the placeholder rows the Wave 5e
     * `match.application.eventbus.listeners.SaveInventoryOnMatchEndedListener`
     * writes (ADR-0022 sibling-isolation forces match-plugin → Core only,
     * so the match listener cannot reach the Paper inventory; this Core
     * listener does).
     *
     * Iterates [Server.getOnlinePlayers] rather than the match's
     * participant list — the latter requires reaching into
     * `IMatchRepository` and racing the match-tear-down pipeline. Saving
     * every online player is a superset that costs O(online) Postgres
     * upserts; cheap enough at Phase 1 PoC scale (≤ 10 players per
     * server) and avoids the race.
     */
    private fun handleMatchEnded(event: MatchEnded) {
        for (player in server.onlinePlayers) {
            val playerId = PlayerId(player.uniqueId)
            try {
                val snapshot = serializer.serialize(player)
                snapshotRepo.save(snapshot)
            } catch (t: Throwable) {
                log.warn(
                    "PlayerInventoryLifecycleListener.handleMatchEnded: save failed for {} (match={}); previous snapshot retained",
                    playerId,
                    event.matchId,
                    t,
                )
            }
        }
        if (log.isDebugEnabled) {
            log.debug(
                "PlayerInventoryLifecycleListener.handleMatchEnded: snapshotted {} online players for match={}",
                server.onlinePlayers.size,
                event.matchId,
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
