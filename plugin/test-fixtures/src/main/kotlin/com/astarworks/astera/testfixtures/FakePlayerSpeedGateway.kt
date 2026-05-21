package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway
import com.astarworks.astera.core.application.port.outbound.SpeedMutationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.SpeedAxis

/**
 * Capture-only fake [IPlayerSpeedGateway] (Wave 5k, ADR-0080).
 *
 * Stores state in-memory without scheduling or events — for use-case tests
 * that only assert on "the gateway was called with X". For TTL expiry tests
 * use [com.astarworks.astera.core.application.service.InMemoryPlayerSpeedGateway]
 * with a [FakeScheduler].
 */
public class FakePlayerSpeedGateway : IPlayerSpeedGateway {

    public sealed interface Call {
        public data class SetMultiplier(
            val playerId: PlayerId,
            val axis: SpeedAxis,
            val multiplier: Double,
            val ttlTicks: Int,
        ) : Call

        public data class ResetMultiplier(val playerId: PlayerId, val axis: SpeedAxis) : Call
        public data class SetAllowFlight(val playerId: PlayerId, val allow: Boolean, val ttlTicks: Int) : Call
        public data class SetSprinting(val playerId: PlayerId, val sprinting: Boolean, val ttlTicks: Int) : Call
        public data class SetNoClip(val playerId: PlayerId, val noClip: Boolean, val ttlTicks: Int) : Call
    }

    private val _calls: MutableList<Call> = mutableListOf()
    private val walkMul: MutableMap<PlayerId, Double> = mutableMapOf()
    private val flyMul: MutableMap<PlayerId, Double> = mutableMapOf()
    private val flightAllowed: MutableSet<PlayerId> = mutableSetOf()
    private val sprinting: MutableSet<PlayerId> = mutableSetOf()
    private val noClip: MutableSet<PlayerId> = mutableSetOf()

    public val calls: List<Call> get() = _calls.toList()

    override fun setMultiplier(
        playerId: PlayerId,
        axis: SpeedAxis,
        multiplier: Double,
        ttlTicks: Int,
    ): Result<Unit, SpeedMutationError> {
        if (!multiplier.isFinite() || multiplier < 0.0) {
            return Result.failure(SpeedMutationError.InvalidValue("multiplier", multiplier))
        }
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        _calls += Call.SetMultiplier(playerId, axis, multiplier, ttlTicks)
        when (axis) {
            SpeedAxis.WALK -> walkMul[playerId] = multiplier
            SpeedAxis.FLY -> flyMul[playerId] = multiplier
        }
        return Result.success(Unit)
    }

    override fun resetMultiplier(playerId: PlayerId, axis: SpeedAxis) {
        _calls += Call.ResetMultiplier(playerId, axis)
        when (axis) {
            SpeedAxis.WALK -> walkMul.remove(playerId)
            SpeedAxis.FLY -> flyMul.remove(playerId)
        }
    }

    override fun setAllowFlight(playerId: PlayerId, allow: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        _calls += Call.SetAllowFlight(playerId, allow, ttlTicks)
        if (allow) flightAllowed += playerId else flightAllowed -= playerId
        return Result.success(Unit)
    }

    override fun setSprinting(playerId: PlayerId, sprinting: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        _calls += Call.SetSprinting(playerId, sprinting, ttlTicks)
        if (sprinting) this.sprinting += playerId else this.sprinting -= playerId
        return Result.success(Unit)
    }

    override fun setNoClip(playerId: PlayerId, noClip: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        _calls += Call.SetNoClip(playerId, noClip, ttlTicks)
        if (noClip) this.noClip += playerId else this.noClip -= playerId
        return Result.success(Unit)
    }

    override fun currentMultiplier(playerId: PlayerId, axis: SpeedAxis): Double? = when (axis) {
        SpeedAxis.WALK -> walkMul[playerId]
        SpeedAxis.FLY -> flyMul[playerId]
    }

    override fun isFlightAllowed(playerId: PlayerId): Boolean = playerId in flightAllowed
    override fun isSprintingForced(playerId: PlayerId): Boolean = playerId in sprinting
    override fun isNoClipActive(playerId: PlayerId): Boolean = playerId in noClip

    public fun reset() {
        _calls.clear()
        walkMul.clear()
        flyMul.clear()
        flightAllowed.clear()
        sprinting.clear()
        noClip.clear()
    }
}
