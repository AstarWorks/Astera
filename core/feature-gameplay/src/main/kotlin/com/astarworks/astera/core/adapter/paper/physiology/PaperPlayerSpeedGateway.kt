package com.astarworks.astera.core.adapter.paper.physiology

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
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
import org.bukkit.Server
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IPlayerSpeedGateway] (Wave 6a-4, ADR-0147) —
 * mirrors `InMemoryPlayerSpeedGateway` event + scheduling shape but
 * writes through to Bukkit `Player` setters.
 *
 * # Bukkit mapping
 *
 *  - `SpeedAxis.WALK` multiplier `m` → `player.walkSpeed = (0.2f * m).coerceIn(-1f, 1f)`
 *    (vanilla `walkSpeed` baseline is 0.2 in `[-1, 1]` per Bukkit docs;
 *    `m = 1.0` ⇒ baseline, `m = 5.0` ⇒ caps at 1.0)
 *  - `SpeedAxis.FLY` multiplier `m` → `player.flySpeed = (0.1f * m).coerceIn(-1f, 1f)`
 *    (vanilla `flySpeed` baseline is 0.1)
 *  - `setAllowFlight` → `player.allowFlight = allow`. Toggling off while
 *    the player is mid-flight Bukkit drops them to ground; acceptable
 *    per ADR-0080 (admin tier; no graceful descent in Phase 1).
 *  - `setSprinting` → `player.isSprinting = sprinting`. Vanilla input
 *    may immediately flip back if the player isn't holding sprint key —
 *    documented Phase 1 PoC trade-off in `IPlayerSpeedGateway` KDoc.
 *  - `setNoClip` → `player.setCollidable(!noClip)`. Bukkit's
 *    `Player.setCollidable(false)` makes the player walk through other
 *    entities; the only stock no-clip-without-GameMode-change path
 *    per `IPlayerSpeedGateway` KDoc.
 *
 * # TTL semantics
 *
 * Matches `InMemoryPlayerSpeedGateway` exactly — `IScheduler.runTicks`
 * one-shot expiry per axis / flag, cancelled on overwrite or explicit
 * reset. Event publishing identical so HUD / analytics subscribers are
 * impl-agnostic.
 *
 * # Offline-player semantics
 *
 * Per `IPlayerSpeedGateway` KDoc (`SpeedMutationError.BackendUnavailable`):
 * every mutation method checks `server.getPlayer(playerId.uuid)` and
 * returns `BackendUnavailable("player offline")` when null. The local
 * state maps still track the request so a later `currentMultiplier` /
 * `isFlightAllowed` query returns the intended setting; the Bukkit-side
 * write simply doesn't fire (logged DEBUG).
 *
 * # Threading
 *
 * Bukkit setters require main thread. Same contract as ADR-0144 /
 * ADR-0145 / ADR-0146 — callers from off-main paths round-trip through
 * `scheduler.run(ExecutionScope.Entity(playerId.uuid), Duration.ZERO, ...)`.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `InMemoryPlayerSpeedGateway` priority 0).
 */
public class PaperPlayerSpeedGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : IPlayerSpeedGateway {

    private val log = LoggerFactory.getLogger(javaClass)

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

        // Bukkit write — best-effort if online.
        onlinePlayer(playerId)?.let { player ->
            val baseline = when (axis) {
                SpeedAxis.WALK -> WALK_BASELINE
                SpeedAxis.FLY -> FLY_BASELINE
            }
            val effective = (baseline * multiplier).toFloat().coerceIn(-1f, 1f)
            when (axis) {
                SpeedAxis.WALK -> player.walkSpeed = effective
                SpeedAxis.FLY -> player.flySpeed = effective
            }
        } ?: log.debug("PaperPlayerSpeedGateway.setMultiplier: target {} offline", playerId)

        if (ttlTicks != Int.MAX_VALUE) {
            expiry[playerId] = scheduler.runLaterTicks(ExecutionScope.Entity(playerId.uuid), ttlTicks.toLong()) {
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
        onlinePlayer(playerId)?.let { player ->
            when (axis) {
                SpeedAxis.WALK -> player.walkSpeed = WALK_BASELINE.toFloat()
                SpeedAxis.FLY -> player.flySpeed = FLY_BASELINE.toFloat()
            }
        }
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

    override fun setAllowFlight(
        playerId: PlayerId,
        allow: Boolean,
        ttlTicks: Int,
    ): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        if (allow) flightAllowed += playerId else flightAllowed -= playerId
        flightExpiry.remove(playerId)?.cancel()
        onlinePlayer(playerId)?.let { it.allowFlight = allow }
        if (allow && ttlTicks != Int.MAX_VALUE) {
            flightExpiry[playerId] = scheduler.runLaterTicks(ExecutionScope.Entity(playerId.uuid), ttlTicks.toLong()) {
                if (playerId in flightAllowed) {
                    flightAllowed -= playerId
                    onlinePlayer(playerId)?.let { it.allowFlight = false }
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

    override fun setSprinting(
        playerId: PlayerId,
        sprinting: Boolean,
        ttlTicks: Int,
    ): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        if (sprinting) this.sprinting += playerId else this.sprinting -= playerId
        sprintingExpiry.remove(playerId)?.cancel()
        onlinePlayer(playerId)?.let { it.isSprinting = sprinting }
        if (sprinting && ttlTicks != Int.MAX_VALUE) {
            sprintingExpiry[playerId] = scheduler.runLaterTicks(ExecutionScope.Entity(playerId.uuid), ttlTicks.toLong()) {
                if (playerId in this.sprinting) {
                    this.sprinting -= playerId
                    onlinePlayer(playerId)?.let { it.isSprinting = false }
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

    override fun setNoClip(
        playerId: PlayerId,
        noClip: Boolean,
        ttlTicks: Int,
    ): Result<Unit, SpeedMutationError> {
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(SpeedMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        if (noClip) this.noClip += playerId else this.noClip -= playerId
        noClipExpiry.remove(playerId)?.cancel()
        // Bukkit no-clip approximation: setCollidable(!noClip) per
        // IPlayerSpeedGateway KDoc. Walks-through-entities only; world
        // geometry collision still applies (true no-clip requires
        // GameMode.SPECTATOR which is ISpectatorGateway scope).
        onlinePlayer(playerId)?.let { it.isCollidable = !noClip }
        if (noClip && ttlTicks != Int.MAX_VALUE) {
            noClipExpiry[playerId] = scheduler.runLaterTicks(ExecutionScope.Entity(playerId.uuid), ttlTicks.toLong()) {
                if (playerId in this.noClip) {
                    this.noClip -= playerId
                    onlinePlayer(playerId)?.let { it.isCollidable = true }
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

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    public companion object {
        /** Bukkit's vanilla `Player.walkSpeed` baseline (`[-1, 1]` range). */
        public const val WALK_BASELINE: Double = 0.2
        /** Bukkit's vanilla `Player.flySpeed` baseline. */
        public const val FLY_BASELINE: Double = 0.1
    }
}
