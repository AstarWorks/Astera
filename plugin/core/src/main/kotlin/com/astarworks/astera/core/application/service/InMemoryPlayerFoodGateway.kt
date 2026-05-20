package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.FoodMutationError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerFoodChanged
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.math.roundToInt

/**
 * In-memory [IPlayerFoodGateway] (Wave 5k, ADR-0080).
 *
 * Single-JVM default at priority 0; Wave 6a `PaperPlayerFoodGateway` overlays
 * at priority 100 and cancels `FoodLevelChangeEvent` while [isHungerSuppressed]
 * is true.
 */
public class InMemoryPlayerFoodGateway(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : IPlayerFoodGateway {

    private val suppressed: MutableSet<PlayerId> = mutableSetOf()
    private val foodLevel: MutableMap<PlayerId, Int> = mutableMapOf()
    private val saturation: MutableMap<PlayerId, Float> = mutableMapOf()

    override fun suppressHungerHud(playerId: PlayerId, suppress: Boolean): Result<Unit, FoodMutationError> {
        if (suppress) {
            suppressed += playerId
            foodLevel[playerId] = 20
            saturation[playerId] = 20.0f
        } else {
            suppressed -= playerId
        }
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = foodLevel[playerId] ?: 20,
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
        val level = (manaPercent * 20.0f).roundToInt().coerceIn(0, 20)
        foodLevel[playerId] = level
        saturation[playerId] = 0.0f
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
        if (level < 0 || level > 20) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        foodLevel[playerId] = level
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
        val cap = (foodLevel[playerId] ?: 20).toFloat()
        val clamped = saturation.coerceAtMost(cap)
        this.saturation[playerId] = clamped
        eventBus.publish(
            PlayerFoodChanged(
                playerId = playerId,
                foodLevel = foodLevel[playerId] ?: 20,
                saturation = clamped,
                suppressed = playerId in suppressed,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun isHungerSuppressed(playerId: PlayerId): Boolean = playerId in suppressed
    override fun getFoodLevel(playerId: PlayerId): Int? = foodLevel[playerId]
    override fun getSaturation(playerId: PlayerId): Float? = saturation[playerId]
}
