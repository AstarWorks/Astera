package com.astarworks.astera.core.adapter.paper.physiology

import com.astarworks.astera.core.application.port.outbound.FoodMutationError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerFoodChanged
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

/**
 * Paper concrete of [IPlayerFoodGateway] (Wave 6a-5, ADR-0148) — mirrors
 * `InMemoryPlayerFoodGateway`'s event-publication shape and internal state
 * tracking while writing through to Bukkit `Player` setters and cancelling
 * Bukkit's `FoodLevelChangeEvent` for suppressed players.
 *
 * # Bukkit mapping
 *
 *  - `suppressHungerHud(playerId, true)` → adds to internal `suppressed`
 *    set, writes `player.foodLevel = 20` + `player.saturation = 20.0f`.
 *    From then on `onFoodLevelChange` cancels every vanilla hunger tick
 *    for this player and re-asserts full bar (belt-and-suspenders since
 *    `FoodLevelChangeEvent` cancellation is the documented path but
 *    plugins may set foodLevel directly bypassing the event).
 *  - `setHungerAsMana` / `setFoodLevel` / `setSaturation` map 1:1 to
 *    `Player.foodLevel` / `Player.saturation` Bukkit setters after the
 *    same validation as the InMemory impl.
 *
 * # Listener pattern (new in Astera core Paper adapters)
 *
 * Unlike the other physiology gateways (Wave 6a-1 to 6a-4 — knockback /
 * health / damage / speed) which are pure write-through, food suppression
 * needs an inbound Bukkit hook to neutralise the vanilla hunger loop. The
 * gateway therefore implements `org.bukkit.event.Listener` and exposes
 * [start] which `AsteraCorePlugin.onEnable` calls after construction. Two
 * sibling listeners already exist in this module
 * (`adapter/paper/event/BukkitEventAdapter.kt`,
 * `adapter/paper/gui/PaperGuiListener.kt`) so the pattern itself isn't
 * novel — just first inside `adapter/paper/physiology/`.
 *
 * # Offline-player semantics
 *
 * Per `IPlayerFoodGateway` KDoc + matching the Wave 6a-4 speed gateway:
 * every mutation method checks `server.getPlayer(playerId.uuid)` and
 * returns `FoodMutationError.BackendUnavailable("player offline")` when
 * null. The `suppressed` set still tracks the request so a later
 * `isHungerSuppressed` / `getFoodLevel` query honours the intent; the
 * Bukkit-side write simply doesn't fire (logged DEBUG). Reads return
 * `null` when the player is offline.
 *
 * # Threading
 *
 * Bukkit `Player.setFoodLevel` / `setSaturation` / `setCollidable` all
 * require the main thread. Same contract as ADR-0144 / 0145 / 0146 /
 * 0147 — off-main callers must round-trip through
 * `scheduler.runLater(Duration.ZERO, ...)`. The `onFoodLevelChange`
 * event handler is invoked on the main thread by Bukkit so no extra
 * scheduling is needed there.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `InMemoryPlayerFoodGateway` priority 0). The plugin must also call
 * [start] to register the event listener; without that the suppression
 * mode degrades silently to "write once at suppress-time, vanilla
 * decrements freely afterwards".
 *
 * @see IPlayerFoodGateway for the full contract (suppression vs
 *      mana-bar modes, validation rules).
 */
public class PaperPlayerFoodGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : IPlayerFoodGateway, Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    private val suppressed: MutableSet<PlayerId> = mutableSetOf()
    private val foodLevel: MutableMap<PlayerId, Int> = mutableMapOf()
    private val saturation: MutableMap<PlayerId, Float> = mutableMapOf()

    /**
     * Register this gateway as a Bukkit `Listener` so [onFoodLevelChange]
     * fires. Called by `AsteraCorePlugin.onEnable` after construction.
     *
     * Idempotent on Bukkit's side: re-registering the same listener
     * instance is a no-op per Paper plugin manager semantics, but the
     * orchestrator should only call this once at boot.
     */
    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * Cancel vanilla hunger decrement / increment for players currently
     * in [suppressed] and re-assert the full-bar invariant (belt-and-
     * suspenders against plugins that set `foodLevel` directly without
     * firing this event).
     *
     * Runs at `EventPriority.HIGH` so most gameplay plugins see the
     * original event (they typically run NORMAL); `ignoreCancelled =
     * true` so we skip work if another plugin already cancelled.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val entity = event.entity as? Player ?: return
        val playerId = PlayerId(entity.uniqueId)
        if (playerId !in suppressed) return
        event.isCancelled = true
        entity.foodLevel = MAX_FOOD_LEVEL
        entity.saturation = MAX_SATURATION
    }

    override fun suppressHungerHud(playerId: PlayerId, suppress: Boolean): Result<Unit, FoodMutationError> {
        if (suppress) {
            suppressed += playerId
            foodLevel[playerId] = MAX_FOOD_LEVEL
            saturation[playerId] = MAX_SATURATION
        } else {
            suppressed -= playerId
        }
        val player = onlinePlayer(playerId)
        if (player == null) {
            log.debug("PaperPlayerFoodGateway.suppressHungerHud: target {} offline", playerId)
            return Result.failure(FoodMutationError.BackendUnavailable("player offline"))
        }
        if (suppress) {
            player.foodLevel = MAX_FOOD_LEVEL
            player.saturation = MAX_SATURATION
        }
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = foodLevel[playerId] ?: MAX_FOOD_LEVEL,
                saturation = saturation[playerId] ?: 0.0f,
                suppressed = suppress,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun setHungerAsMana(playerId: PlayerId, manaPercent: Float): Result<Unit, FoodMutationError> {
        if (!manaPercent.isFinite() || manaPercent < 0.0f || manaPercent > 1.0f) {
            return Result.failure(FoodMutationError.InvalidValue("manaPercent", manaPercent.toDouble()))
        }
        val level = (manaPercent * MAX_FOOD_LEVEL.toFloat()).roundToInt().coerceIn(0, MAX_FOOD_LEVEL)
        foodLevel[playerId] = level
        saturation[playerId] = 0.0f
        val player = onlinePlayer(playerId)
        if (player == null) {
            log.debug("PaperPlayerFoodGateway.setHungerAsMana: target {} offline", playerId)
            return Result.failure(FoodMutationError.BackendUnavailable("player offline"))
        }
        player.foodLevel = level
        player.saturation = 0.0f
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = level,
                saturation = 0.0f,
                suppressed = false,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun setFoodLevel(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError> {
        if (level < 0 || level > MAX_FOOD_LEVEL) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        foodLevel[playerId] = level
        val player = onlinePlayer(playerId)
        if (player == null) {
            log.debug("PaperPlayerFoodGateway.setFoodLevel: target {} offline", playerId)
            return Result.failure(FoodMutationError.BackendUnavailable("player offline"))
        }
        player.foodLevel = level
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = level,
                saturation = saturation[playerId] ?: 0.0f,
                suppressed = playerId in suppressed,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun setSaturation(playerId: PlayerId, saturation: Float): Result<Unit, FoodMutationError> {
        if (!saturation.isFinite() || saturation < 0.0f) {
            return Result.failure(FoodMutationError.InvalidValue("saturation", saturation.toDouble()))
        }
        val cap = (foodLevel[playerId] ?: MAX_FOOD_LEVEL).toFloat()
        val clamped = saturation.coerceAtMost(cap)
        this.saturation[playerId] = clamped
        val player = onlinePlayer(playerId)
        if (player == null) {
            log.debug("PaperPlayerFoodGateway.setSaturation: target {} offline", playerId)
            return Result.failure(FoodMutationError.BackendUnavailable("player offline"))
        }
        player.saturation = clamped
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = foodLevel[playerId] ?: MAX_FOOD_LEVEL,
                saturation = clamped,
                suppressed = playerId in suppressed,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun isHungerSuppressed(playerId: PlayerId): Boolean = playerId in suppressed

    override fun getFoodLevel(playerId: PlayerId): Int? = onlinePlayer(playerId)?.foodLevel

    override fun getSaturation(playerId: PlayerId): Float? = onlinePlayer(playerId)?.saturation

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    public companion object {
        /** Vanilla Minecraft food-level ceiling (`Player.foodLevel` is `[0, 20]`). */
        public const val MAX_FOOD_LEVEL: Int = 20

        /**
         * Vanilla saturation ceiling at full food (`Player.saturation` is
         * capped at `foodLevel` per Bukkit docs; 20 is the ceiling when
         * the bar is locked at max).
         */
        public const val MAX_SATURATION: Float = 20.0f
    }
}
