package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Cancels click / pickup / interact while the player's [PlayerSession]
 * state is [SessionState.Loading] (Wave 6b-3, ADR-0158).
 *
 * # Why this exists
 *
 * Wave 5e (`PlayerSession` KDoc) reserved [SessionState.Loading] as the
 * action-lock window during `RestoreInventoryUseCase` /
 * `MatchSpawnUseCase`. Without enforcement the lock was decorative — a
 * player could click the freshly restored hotbar mid-deserialize and
 * shuffle items into wrong slots. This enforcer is the missing
 * Bukkit-side guard: three high-priority listeners that consult
 * [IPlayerSessionRepository] and cancel the event when the player is
 * Loading.
 *
 * # Event coverage
 *
 *  - [InventoryClickEvent] — covers GUI interactions and own-inventory
 *    drags. Runs at [EventPriority.HIGH] + `ignoreCancelled = false` so
 *    we cancel even events lower priority plugins explicitly
 *    un-cancelled.
 *  - [EntityPickupItemEvent] — covers ground pickup (vanilla + projectile
 *    / item-frame drops). Filtered to `entity is Player` so mob pickup
 *    paths are untouched.
 *  - [PlayerInteractEvent] — covers right-click / left-click / physical
 *    contact (pressure plates etc.). Runs at [EventPriority.HIGH] with
 *    `ignoreCancelled = true` because other lock plugins may have
 *    already cancelled and re-running our cancellation buys nothing.
 *
 * # Threading + isolation
 *
 * All three handlers run on the Bukkit main thread (Paper dispatches
 * events synchronously); session-repo lookups are in-memory by Wave 5e
 * design (no Postgres overlay) so the per-event lookup is O(1) hash get.
 * Per-handler exceptions are swallowed + WARN-logged so a faulty session
 * repository cannot stall the Bukkit dispatch loop (same posture as
 * `BukkitEventAdapter.safePublish` — ADR-0152 §A — and
 * `PaperPlayerFoodGateway.onFoodLevelChange`).
 *
 * # Registration
 *
 * Construct in `AsteraCorePlugin.onEnable` and call [start] with the
 * `JavaPlugin` instance. No teardown work needed beyond Paper's
 * automatic `HandlerList.unregisterAll(plugin)` at disable.
 */
public class InventoryActionLockEnforcer(
    private val sessions: IPlayerSessionRepository,
    private val scheduler: IScheduler,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Register all three Bukkit listeners. Called by
     * `AsteraCorePlugin.onEnable` after construction.
     */
    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * Cancel inventory clicks (own inventory + GUIs) while the player is
     * in [SessionState.Loading]. `ignoreCancelled = false` so we still
     * cancel even if a lower-priority plugin un-cancelled the event.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (isLoading(player)) {
            event.isCancelled = true
        }
    }

    /**
     * Cancel item pickup while the player is in [SessionState.Loading].
     * `EntityPickupItemEvent.entity` may be any LivingEntity; we filter
     * to Player to avoid touching mob pickup behaviour.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (isLoading(player)) {
            event.isCancelled = true
        }
    }

    /**
     * Cancel right-click / left-click / physical interactions while the
     * player is in [SessionState.Loading]. `ignoreCancelled = true`
     * because another lock plugin's cancellation is sufficient — running
     * our check on top adds no signal.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (isLoading(player)) {
            event.isCancelled = true
        }
    }

    /**
     * True when the player has a tracked session whose state is
     * [SessionState.Loading]. Returns false for untracked players (no
     * session row yet — never been through [PlayerInventoryLifecycleListener]
     * join handler).
     *
     * Wrapped in try/catch so a faulty session repository cannot escape
     * back into the Bukkit dispatch loop. On failure the player is
     * treated as not-loading (fail-open) so a transient repo error does
     * not freeze the player out of every interaction.
     */
    private fun isLoading(player: Player): Boolean = try {
        sessions.find(PlayerId(player.uniqueId))?.state == SessionState.Loading
    } catch (t: Throwable) {
        log.warn(
            "InventoryActionLockEnforcer.isLoading: session lookup failed for {}; treating as not-loading",
            player.uniqueId,
            t,
        )
        false
    }

    /**
     * Currently unused — reserved for a future sub-wave that schedules a
     * "lock expired but state never cleared" watchdog. Kept on the
     * constructor signature so wiring stays stable across the upcoming
     * change.
     */
    @Suppress("unused")
    private val schedulerRef: IScheduler = scheduler
}
