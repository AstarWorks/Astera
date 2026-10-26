package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.FoodMutationError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerFoodChanged
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IPlayerFoodGateway] (Wave 5k, ADR-0080).
 *
 * Single-JVM default at priority 0; `PaperPlayerFoodGateway` overlays at
 * priority 100 and writes through to Bukkit.
 */
public class InMemoryPlayerFoodGateway(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : IPlayerFoodGateway {

    private val foodLevel: MutableMap<PlayerId, Int> = mutableMapOf()
    private val saturation: MutableMap<PlayerId, Float> = mutableMapOf()

    override fun mirrorEnergy(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError> {
        if (level < 0 || level > 20) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        foodLevel[playerId] = level
        saturation[playerId] = 0.0f
        publish(playerId)
        return Result.success(Unit)
    }

    override fun setFoodLevel(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError> {
        if (level < 0 || level > 20) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        foodLevel[playerId] = level
        publish(playerId)
        return Result.success(Unit)
    }

    override fun setSaturation(playerId: PlayerId, saturation: Float): Result<Unit, FoodMutationError> {
        if (!saturation.isFinite() || saturation < 0.0f) {
            return Result.failure(FoodMutationError.InvalidValue("saturation", saturation.toDouble()))
        }
        val cap = (foodLevel[playerId] ?: 20).toFloat()
        this.saturation[playerId] = saturation.coerceAtMost(cap)
        publish(playerId)
        return Result.success(Unit)
    }

    override fun getFoodLevel(playerId: PlayerId): Int? = foodLevel[playerId]
    override fun getSaturation(playerId: PlayerId): Float? = saturation[playerId]

    private fun publish(playerId: PlayerId) {
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = foodLevel[playerId] ?: 20,
                saturation = saturation[playerId] ?: 0.0f,
                atTick = scheduler.currentTick(),
            ),
        )
    }
}
