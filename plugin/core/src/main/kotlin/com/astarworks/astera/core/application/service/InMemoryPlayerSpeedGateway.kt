package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.SpeedMutationError
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerFlightToggled
import com.astarworks.astera.core.domain.event.PlayerNoClipChanged
import com.astarworks.astera.core.domain.event.PlayerSpeedChanged
import com.astarworks.astera.core.domain.event.PlayerSprintingChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.SpeedAxis

/**
 * In-memory [IPlayerSpeedGateway] (Wave 5k, ADR-0080).
 *
 * Single-JVM default at priority 0; Wave 6a `PaperPlayerSpeedGateway` overlays
 * at priority 100. Auto-reset is scheduled via [IScheduler] one-shot tasks
 * (mirrors Wave 5g [InMemoryPlayerMovementGateway] approach).
 */
public class InMemoryPlayerSpeedGateway(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : IPlayerSpeedGateway {

    private val walkMul: MutableMap<PlayerId, Double> = mutableMapOf()
    private val walkExpiry: MutableMap<PlayerId, IScheduler.TaskHandle> = mutableMapOf()
    private val flyMul: MutableMap<PlayerId, Double> = mutableMapOf()
    private val flyExpiry: MutableMap<PlayerId, IScheduler.TaskHandle> = mutableMapOf()

    private val flightAllowed: MutableSet<PlayerId> = mutableSetOf()
    private val flightExpiry: MutableMap<PlayerId, IScheduler.TaskHandle> = mutableMapOf()
    private val sprinting: MutableSet<PlayerId> = mutableSetOf()
    private val sprintingExpiry: MutableMap<PlayerId, IScheduler.TaskHandle> = mutableMapOf()
    private val noClip: MutableSet<PlayerId> = mutableSetOf()
    private val noClipExpiry: MutableMap<PlayerId, IScheduler.TaskHandle> = mutableMapOf()

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
        val (storage, expiry) = when (axis) {
            SpeedAxis.WALK -> walkMul to walkExpiry
            SpeedAxis.FLY -> flyMul to flyExpiry
        }
        val previous = storage[playerId]
        storage[playerId] = multiplier
        expiry.remove(playerId)?.cancel()
        if (ttlTicks != Int.MAX_VALUE) {
            expiry[playerId] = scheduler.runLaterTicks(ttlTicks.toLong()) {
                resetMultiplier(playerId, axis)
            }
        }
        eventBus.publish(
            PlayerSpeedChanged(
                playerId = playerId,
                axis = axis,
                oldMultiplier = previous,
                newMultiplier = multiplier,
                ttlTicks = ttlTicks,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun resetMultiplier(playerId: PlayerId, axis: SpeedAxis) {
        val (storage, expiry) = when (axis) {
            SpeedAxis.WALK -> walkMul to walkExpiry
            SpeedAxis.FLY -> flyMul to flyExpiry
        }
        val previous = storage.remove(playerId) ?: return
        expiry.remove(playerId)?.cancel()
        eventBus.publish(
            PlayerSpeedChanged(
                playerId = playerId,
                axis = axis,
                oldMultiplier = previous,
                newMultiplier = 1.0,
                ttlTicks = 0,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    override fun setAllowFlight(playerId: PlayerId, allow: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        if (allow) flightAllowed += playerId else flightAllowed -= playerId
        flightExpiry.remove(playerId)?.cancel()
        if (allow && ttlTicks != Int.MAX_VALUE) {
            flightExpiry[playerId] = scheduler.runLaterTicks(ttlTicks.toLong()) {
                if (playerId in flightAllowed) {
                    flightAllowed -= playerId
                    eventBus.publish(
                        PlayerFlightToggled(
                            playerId = playerId,
                            allowed = false,
                            ttlTicks = 0,
                            atTick = scheduler.currentTick(),
                        ),
                    )
                }
            }
        }
        eventBus.publish(
            PlayerFlightToggled(
                playerId = playerId,
                allowed = allow,
                ttlTicks = ttlTicks,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun setSprinting(playerId: PlayerId, sprinting: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        if (sprinting) this.sprinting += playerId else this.sprinting -= playerId
        sprintingExpiry.remove(playerId)?.cancel()
        if (sprinting && ttlTicks != Int.MAX_VALUE) {
            sprintingExpiry[playerId] = scheduler.runLaterTicks(ttlTicks.toLong()) {
                if (playerId in this.sprinting) {
                    this.sprinting -= playerId
                    eventBus.publish(
                        PlayerSprintingChanged(
                            playerId = playerId,
                            sprinting = false,
                            ttlTicks = 0,
                            atTick = scheduler.currentTick(),
                        ),
                    )
                }
            }
        }
        eventBus.publish(
            PlayerSprintingChanged(
                playerId = playerId,
                sprinting = sprinting,
                ttlTicks = ttlTicks,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun setNoClip(playerId: PlayerId, noClip: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        if (noClip) this.noClip += playerId else this.noClip -= playerId
        noClipExpiry.remove(playerId)?.cancel()
        if (noClip && ttlTicks != Int.MAX_VALUE) {
            noClipExpiry[playerId] = scheduler.runLaterTicks(ttlTicks.toLong()) {
                if (playerId in this.noClip) {
                    this.noClip -= playerId
                    eventBus.publish(
                        PlayerNoClipChanged(
                            playerId = playerId,
                            noClip = false,
                            ttlTicks = 0,
                            atTick = scheduler.currentTick(),
                        ),
                    )
                }
            }
        }
        eventBus.publish(
            PlayerNoClipChanged(
                playerId = playerId,
                noClip = noClip,
                ttlTicks = ttlTicks,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun currentMultiplier(playerId: PlayerId, axis: SpeedAxis): Double? = when (axis) {
        SpeedAxis.WALK -> walkMul[playerId]
        SpeedAxis.FLY -> flyMul[playerId]
    }

    override fun isFlightAllowed(playerId: PlayerId): Boolean = playerId in flightAllowed
    override fun isSprintingForced(playerId: PlayerId): Boolean = playerId in sprinting
    override fun isNoClipActive(playerId: PlayerId): Boolean = playerId in noClip
}
