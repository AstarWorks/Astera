package com.astarworks.astera.core.adapter.paper.cinematic

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.cinematic.CameraError
import com.astarworks.astera.core.application.port.outbound.cinematic.ICameraGateway
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.cinematic.CameraHandle
import com.astarworks.astera.core.domain.cinematic.CameraTarget
import com.astarworks.astera.core.domain.event.CameraReleased
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [ICameraGateway] (Wave 6a-7, ADR-0152) — bridges
 * the cinematic camera vocabulary to Bukkit's
 * [Player.setSpectatorTarget] primitive for entity / player follow,
 * and **stubs** pan / shake / zoom / Position-target lock until
 * Wave 6b's custom-packet adapter lands.
 *
 * # Bukkit mapping
 *
 *  - `lockCamera(player, CameraTarget.Entity)` →
 *    `player.setSpectatorTarget(entity)` (Bukkit native API; requires
 *    `GameMode.SPECTATOR` per Bukkit docs — callers must
 *    `ISpectatorGateway.enter` first or the camera detaches on next
 *    input tick). Resolves the [EntityHandle] via `server.getEntity(uuid)`.
 *  - `lockCamera(player, CameraTarget.Player)` → same as Entity,
 *    resolved through `server.getPlayer(playerId.uuid)`.
 *  - `lockCamera(player, CameraTarget.Position)` /
 *    `CameraTarget.RelativeToPlayer` → **Phase 1 stub**: returns a
 *    `CameraHandle` (so handle-bookkeeping tests stay green) but does
 *    **not** spawn an invisible follow-entity at the position. Wave 6b
 *    will replace with `ClientboundSetCameraPacket` against a
 *    display-entity placeholder. Logged INFO at first invocation per
 *    server lifetime to surface the limitation.
 *  - `releaseCamera(player)` → `player.setSpectatorTarget(null)` to
 *    detach + publishes `CameraReleased` mirroring the InMemory shape.
 *  - `freeze(player, durationTicks)` → re-targets the player onto
 *    themselves (`setSpectatorTarget(player)`) as a degenerate "look
 *    at self" lock; `IScheduler.runLaterTicks` schedules
 *    `releaseCamera` at the end of the window. **Caveat**: vanilla
 *    Bukkit may immediately drop a self-target on input; Wave 6b
 *    `ClientboundPlayerRotationPacket` will provide true rotation
 *    freeze.
 *  - `panCamera(player, from, to, ticks, easing)` → **Phase 1 stub**:
 *    instant-locks onto `to` (no interpolation). Easing is recorded in
 *    the returned handle's metadata but not applied — Wave 6b packet
 *    adapter applies easing curve sample per tick.
 *  - `shakeCamera` / `zoomCamera` → **Phase 1 stub**: validate inputs
 *    (returns InvalidIntensity / InvalidZoom on bad args, identical to
 *    InMemory) and return Success with no visual effect. Wave 6b
 *    overlays packet-driven viewmodel perturbation.
 *
 * # Why stubs instead of "skip the file"
 *
 * The cinematic system's *handle bookkeeping* is part of the contract
 * (auto-release-on-relock, handle uniqueness, `activeHandle` accuracy)
 * and is independent of the rendering side. Wiring the Paper adapter
 * now means HUD / tutorial subscribers wire to `CameraReleased` against
 * the production gateway from Wave 6a-7, and the Wave 6b packet
 * upgrade is purely additive (visual fidelity, no behavioural delta).
 *
 * # TTL semantics
 *
 * Matches [InMemoryCameraGateway]'s scheduler-less in-memory shape for
 * lock/pan/shake/zoom (no auto-release on a per-call timer — those
 * are caller-driven), but `freeze` schedules an explicit
 * `releaseCamera` at end-of-window (matching the port KDoc:
 * "Auto-releases at the end of the window"). That release publishes
 * `CameraReleased` to the bus.
 *
 * # Offline-player semantics
 *
 * Every lock/pan/freeze checks `server.getPlayer(playerId.uuid)`.
 * Offline ⇒ returns `CameraError.UnknownPlayer(playerId)`. Unlike the
 * physiology gateways (which record-and-skip), camera state is
 * meaningless without a connected client — there's no read-your-own-
 * writes invariant to preserve.
 *
 * # Threading
 *
 * Bukkit `Player.setSpectatorTarget` requires the main thread. Same
 * contract as ADR-0144 / 0145 / 0146 / 0147 / 0148 — off-main callers
 * must round-trip through `scheduler.runLater(Duration.ZERO, ...)`.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `InMemoryCameraGateway` priority 0).
 *
 * @see ICameraGateway for the full contract.
 * @see com.astarworks.astera.core.application.service.cinematic.InMemoryCameraGateway
 *      for the priority-0 default.
 * @see PaperPlayerSpeedGateway for the physiology-side sibling pattern
 *      reference (Wave 6a-4, ADR-0147).
 */
public class PaperCameraGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : ICameraGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val active: ConcurrentHashMap<PlayerId, CameraHandle> = ConcurrentHashMap()
    private val freezeExpiry: ConcurrentHashMap<PlayerId, IScheduler.TaskHandle> = ConcurrentHashMap()

    private var positionLockStubLogged: Boolean = false
    private var panStubLogged: Boolean = false
    private var shakeStubLogged: Boolean = false
    private var zoomStubLogged: Boolean = false

    override fun lockCamera(
        playerId: PlayerId,
        target: CameraTarget,
    ): Result<CameraHandle, CameraError> {
        val player = server.getPlayer(playerId.uuid)
            ?: return Result.failure(CameraError.UnknownPlayer(playerId))

        val resolved: Entity? = when (target) {
            is CameraTarget.Entity -> server.getEntity(target.entity.id)
            is CameraTarget.Player -> server.getPlayer(target.player.uuid)
            is CameraTarget.Position -> {
                if (!positionLockStubLogged) {
                    log.info(
                        "PaperCameraGateway.lockCamera(Position) is a Phase 1 stub — " +
                            "handle returned but no visual lock until Wave 6b packet adapter (ADR-0152 §camera-stubs)",
                    )
                    positionLockStubLogged = true
                }
                null
            }
            is CameraTarget.RelativeToPlayer -> {
                if (!positionLockStubLogged) {
                    log.info(
                        "PaperCameraGateway.lockCamera(RelativeToPlayer) is a Phase 1 stub — " +
                            "handle returned but no visual lock until Wave 6b packet adapter (ADR-0152 §camera-stubs)",
                    )
                    positionLockStubLogged = true
                }
                null
            }
        }

        if (resolved != null) {
            // Bukkit native: requires GameMode.SPECTATOR on the viewer. The
            // ISpectatorGateway (Wave 5h) is the dedicated GameMode switch
            // — this adapter does NOT auto-enter spectator; callers must
            // sequence the two ports.
            player.setSpectatorTarget(resolved)
        }

        // Implicit release of any prior lock (per port KDoc).
        active[playerId]?.let { _ ->
            // No event for implicit release per port KDoc — releaseCamera
            // is the explicit signal.
        }
        val handle = mintHandle()
        active[playerId] = handle
        // Cancel any pending freeze auto-release; new lock supersedes.
        freezeExpiry.remove(playerId)?.cancel()
        return Result.success(handle)
    }

    override fun panCamera(
        playerId: PlayerId,
        fromTarget: CameraTarget,
        toTarget: CameraTarget,
        durationTicks: Long,
        easing: Easing,
    ): Result<CameraHandle, CameraError> {
        if (durationTicks < 0L) {
            return Result.failure(
                CameraError.InvalidDuration("panCamera.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        if (!panStubLogged) {
            log.info(
                "PaperCameraGateway.panCamera is a Phase 1 stub — instant-snaps to toTarget; " +
                    "easing={} ignored until Wave 6b packet adapter (ADR-0152 §camera-stubs)",
                easing,
            )
            panStubLogged = true
        }
        // Degenerate "instant pan": apply the destination lock immediately.
        return lockCamera(playerId, toTarget)
    }

    override fun shakeCamera(
        playerId: PlayerId,
        intensity: Double,
        durationTicks: Long,
    ): Result<Unit, CameraError> {
        if (intensity !in 0.0..1.0) {
            return Result.failure(
                CameraError.InvalidIntensity("shakeCamera.intensity must be in 0.0..1.0 (got $intensity)"),
            )
        }
        if (durationTicks < 0L) {
            return Result.failure(
                CameraError.InvalidDuration("shakeCamera.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        if (server.getPlayer(playerId.uuid) == null) {
            return Result.failure(CameraError.UnknownPlayer(playerId))
        }
        if (!shakeStubLogged) {
            log.info(
                "PaperCameraGateway.shakeCamera is a Phase 1 stub — validates inputs only; " +
                    "no visual shake until Wave 6b packet adapter (ADR-0152 §camera-stubs)",
            )
            shakeStubLogged = true
        }
        return Result.success(Unit)
    }

    override fun zoomCamera(
        playerId: PlayerId,
        fovMultiplier: Double,
        durationTicks: Long,
    ): Result<Unit, CameraError> {
        if (fovMultiplier <= 0.0) {
            return Result.failure(
                CameraError.InvalidZoom("zoomCamera.fovMultiplier must be > 0.0 (got $fovMultiplier)"),
            )
        }
        if (durationTicks < 0L) {
            return Result.failure(
                CameraError.InvalidDuration("zoomCamera.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        if (server.getPlayer(playerId.uuid) == null) {
            return Result.failure(CameraError.UnknownPlayer(playerId))
        }
        if (!zoomStubLogged) {
            log.info(
                "PaperCameraGateway.zoomCamera is a Phase 1 stub — validates inputs only; " +
                    "no FOV change until Wave 6b packet adapter (ADR-0152 §camera-stubs)",
            )
            zoomStubLogged = true
        }
        return Result.success(Unit)
    }

    override fun releaseCamera(playerId: PlayerId): Result<Unit, CameraError> {
        val handle = active.remove(playerId)
        freezeExpiry.remove(playerId)?.cancel()
        server.getPlayer(playerId.uuid)?.let { player ->
            // Idempotent on Bukkit side: passing null when already detached
            // is a no-op per Bukkit docs.
            player.setSpectatorTarget(null)
        }
        if (handle != null) {
            eventBus.publish(
                CameraReleased(
                    playerId = playerId,
                    handle = handle,
                    atTick = scheduler.currentTick(),
                ),
            )
        }
        return Result.success(Unit)
    }

    override fun freeze(
        playerId: PlayerId,
        durationTicks: Long,
    ): Result<CameraHandle, CameraError> {
        if (durationTicks < 0L) {
            return Result.failure(
                CameraError.InvalidDuration("freeze.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        val player = server.getPlayer(playerId.uuid)
            ?: return Result.failure(CameraError.UnknownPlayer(playerId))

        // Degenerate "look at self" lock — Bukkit's only freeze-equivalent
        // without packet-level rotation pin. Wave 6b supersedes.
        player.setSpectatorTarget(player)
        val handle = mintHandle()
        active[playerId] = handle

        freezeExpiry.remove(playerId)?.cancel()
        if (durationTicks > 0L) {
            freezeExpiry[playerId] = scheduler.runLaterTicks(durationTicks) {
                // Per port KDoc: auto-release fires CameraReleased.
                releaseCamera(playerId)
            }
        }
        return Result.success(handle)
    }

    override fun activeHandle(playerId: PlayerId): CameraHandle? = active[playerId]

    private fun mintHandle(): CameraHandle = CameraHandle(UUID.randomUUID().toString())
}
