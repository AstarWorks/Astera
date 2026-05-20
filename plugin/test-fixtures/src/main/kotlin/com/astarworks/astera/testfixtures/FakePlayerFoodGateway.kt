package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.FoodMutationError
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.math.roundToInt

/**
 * Capture-only fake [IPlayerFoodGateway] (Wave 5k, ADR-0080).
 *
 * Stores state in-memory without publishing events — for use-case tests
 * that just need to assert "the gateway was called with X".
 */
public class FakePlayerFoodGateway : IPlayerFoodGateway {

    public sealed interface Call {
        public data class Suppress(val playerId: PlayerId, val suppress: Boolean) : Call
        public data class AsMana(val playerId: PlayerId, val manaPercent: Float) : Call
        public data class FoodLevel(val playerId: PlayerId, val level: Int) : Call
        public data class Saturation(val playerId: PlayerId, val saturation: Float) : Call
    }

    private val _calls: MutableList<Call> = mutableListOf()
    private val suppressed: MutableSet<PlayerId> = mutableSetOf()
    private val foodLevel: MutableMap<PlayerId, Int> = mutableMapOf()
    private val saturation: MutableMap<PlayerId, Float> = mutableMapOf()

    public val calls: List<Call> get() = _calls.toList()

    override fun suppressHungerHud(playerId: PlayerId, suppress: Boolean): Result<Unit, FoodMutationError> {
        _calls += Call.Suppress(playerId, suppress)
        if (suppress) {
            suppressed += playerId
            foodLevel[playerId] = 20
            saturation[playerId] = 20.0f
        } else {
            suppressed -= playerId
        }
        return Result.success(Unit)
    }

    override fun setHungerAsMana(playerId: PlayerId, manaPercent: Float): Result<Unit, FoodMutationError> {
        if (!manaPercent.isFinite() || manaPercent < 0.0f || manaPercent > 1.0f) {
            return Result.failure(FoodMutationError.InvalidValue("manaPercent", manaPercent.toDouble()))
        }
        _calls += Call.AsMana(playerId, manaPercent)
        foodLevel[playerId] = (manaPercent * 20.0f).roundToInt().coerceIn(0, 20)
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

    override fun isHungerSuppressed(playerId: PlayerId): Boolean = playerId in suppressed
    override fun getFoodLevel(playerId: PlayerId): Int? = foodLevel[playerId]
    override fun getSaturation(playerId: PlayerId): Float? = saturation[playerId]

    public fun reset() {
        _calls.clear()
        suppressed.clear()
        foodLevel.clear()
        saturation.clear()
    }
}
