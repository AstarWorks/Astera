package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.FoodMutationError
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Capture-only fake [IPlayerFoodGateway] (Wave 5k, ADR-0080; ADR-0194).
 *
 * Stores state in-memory without publishing events - for tests that just need
 * to assert "the food bar was written with X".
 */
public class FakePlayerFoodGateway : IPlayerFoodGateway {

    public sealed interface Call {
        public data class Energy(val playerId: PlayerId, val level: Int) : Call
        public data class FoodLevel(val playerId: PlayerId, val level: Int) : Call
        public data class Saturation(val playerId: PlayerId, val saturation: Float) : Call
    }

    private val _calls: MutableList<Call> = mutableListOf()
    private val foodLevel: MutableMap<PlayerId, Int> = mutableMapOf()
    private val saturation: MutableMap<PlayerId, Float> = mutableMapOf()

    public val calls: List<Call> get() = _calls.toList()

    override fun mirrorEnergy(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError> {
        if (level < 0 || level > 20) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        _calls += Call.Energy(playerId, level)
        foodLevel[playerId] = level
        saturation[playerId] = 0.0f
        return Result.success(Unit)
    }

    override fun setFoodLevel(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError> {
        if (level < 0 || level > 20) {
            return Result.failure(FoodMutationError.InvalidValue("level", level.toDouble()))
        }
        _calls += Call.FoodLevel(playerId, level)
        foodLevel[playerId] = level
        return Result.success(Unit)
    }

    override fun setSaturation(playerId: PlayerId, saturation: Float): Result<Unit, FoodMutationError> {
        if (!saturation.isFinite() || saturation < 0.0f) {
            return Result.failure(FoodMutationError.InvalidValue("saturation", saturation.toDouble()))
        }
        _calls += Call.Saturation(playerId, saturation)
        this.saturation[playerId] = saturation
        return Result.success(Unit)
    }

    override fun getFoodLevel(playerId: PlayerId): Int? = foodLevel[playerId]
    override fun getSaturation(playerId: PlayerId): Float? = saturation[playerId]

    public fun reset() {
        _calls.clear()
        foodLevel.clear()
        saturation.clear()
    }
}
