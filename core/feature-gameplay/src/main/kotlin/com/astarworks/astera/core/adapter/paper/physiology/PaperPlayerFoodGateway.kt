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
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IPlayerFoodGateway] (Wave 6a-5, ADR-0148; rewritten by F2-2, ADR-0194).
 *
 * The food bar is the energy bar. This gateway writes the mirrored level
 * through to Bukkit and, as a [Listener], removes every vanilla hunger side
 * effect so that nothing but Astera moves the bar or the player's health:
 *
 *  - `FoodLevelChangeEvent` is cancelled for everyone (exhaustion, eating,
 *    starvation ticks). Astera's own writes use `Player.setFoodLevel`, which
 *    fires no such event, so they pass untouched;
 *  - `EntityRegainHealthEvent` with reason `SATIATED` / `REGEN` is cancelled
 *    (HP is the Astera container, ADR-0149; satiety must not heal);
 *  - `EntityDamageEvent` with cause `STARVATION` is cancelled (energy 0 is food
 *    0, which would otherwise starve the player);
 *  - saturation and exhaustion are zeroed on every mirror write.
 *
 * Registered at priority 100 in `AsteraCorePlugin.onEnable`; [start] registers
 * the listener.
 *
 * Threading: Bukkit food setters need the main thread (ADR-0144..0147).
 */
public class PaperPlayerFoodGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : IPlayerFoodGateway, Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Last level Astera wrote per player, re-asserted if anything else touched the bar. */
    private val mirrored: MutableMap<PlayerId, Int> = mutableMapOf()

    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** Vanilla hunger has no say: the event is cancelled and the mirror re-asserted. */
    @EventHandler(priority = EventPriority.HIGH)
    public fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val entity = event.entity as? Player ?: return
        event.isCancelled = true
        mirrored[PlayerId(entity.uniqueId)]?.let { entity.foodLevel = it }
        entity.saturation = 0.0f
        entity.exhaustion = 0.0f
    }

    /** Satiety-based natural regeneration must not heal: HP is the Astera container. */
    @EventHandler(priority = EventPriority.HIGH)
    public fun onRegainHealth(event: EntityRegainHealthEvent) {
        if (event.entity !is Player) return
        if (event.regainReason == EntityRegainHealthEvent.RegainReason.SATIATED ||
            event.regainReason == EntityRegainHealthEvent.RegainReason.REGEN
        ) {
            event.isCancelled = true
        }
    }

    /** Energy 0 is food 0; the vanilla starvation damage must not follow. */
    @EventHandler(priority = EventPriority.HIGH)
    public fun onStarvation(event: EntityDamageEvent) {
        if (event.entity is Player && event.cause == EntityDamageEvent.DamageCause.STARVATION) {
            event.isCancelled = true
        }
    }

    override fun mirrorEnergy(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError> {
        if (level < 0 || level > MAX_FOOD_LEVEL) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        mirrored[playerId] = level
        val player = onlinePlayer(playerId)
        if (player == null) {
            log.debug("PaperPlayerFoodGateway.mirrorEnergy: target {} offline", playerId)
            return Result.failure(FoodMutationError.BackendUnavailable("player offline"))
        }
        if (player.foodLevel != level) player.foodLevel = level
        player.saturation = 0.0f
        player.exhaustion = 0.0f
        publish(playerId, level, 0.0f)
        return Result.success(Unit)
    }

    override fun setFoodLevel(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError> {
        if (level < 0 || level > MAX_FOOD_LEVEL) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        mirrored[playerId] = level
        val player = onlinePlayer(playerId)
            ?: return Result.failure(FoodMutationError.BackendUnavailable("player offline"))
        player.foodLevel = level
        publish(playerId, level, player.saturation)
        return Result.success(Unit)
    }

    override fun setSaturation(playerId: PlayerId, saturation: Float): Result<Unit, FoodMutationError> {
        if (!saturation.isFinite() || saturation < 0.0f) {
            return Result.failure(FoodMutationError.InvalidValue("saturation", saturation.toDouble()))
        }
        val player = onlinePlayer(playerId)
            ?: return Result.failure(FoodMutationError.BackendUnavailable("player offline"))
        val clamped = saturation.coerceAtMost(player.foodLevel.toFloat())
        player.saturation = clamped
        publish(playerId, player.foodLevel, clamped)
        return Result.success(Unit)
    }

    override fun getFoodLevel(playerId: PlayerId): Int? = onlinePlayer(playerId)?.foodLevel

    override fun getSaturation(playerId: PlayerId): Float? = onlinePlayer(playerId)?.saturation

    private fun publish(playerId: PlayerId, level: Int, saturation: Float) {
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = level,
                saturation = saturation,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    public companion object {
        /** Vanilla Minecraft food-level ceiling (`Player.foodLevel` is `[0, 20]`). */
        public const val MAX_FOOD_LEVEL: Int = 20
    }
}
