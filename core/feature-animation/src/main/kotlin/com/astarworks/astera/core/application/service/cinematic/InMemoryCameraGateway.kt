package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.cinematic.CameraError
import com.astarworks.astera.core.application.port.outbound.cinematic.ICameraGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.cinematic.CameraHandle
import com.astarworks.astera.core.domain.cinematic.CameraTarget
import com.astarworks.astera.core.domain.event.CameraReleased
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [ICameraGateway] (Wave 5cinematic, ADR-0110).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6 lands `PaperCameraGateway`
 * over Paper packet primitives at higher priority and shadows this impl.
 *
 * **Not thread-safe** beyond what [ConcurrentHashMap] provides. The Paper
 * adapter pipeline runs on the main thread; the concurrent map is
 * precautionary for cross-thread packet handlers in Wave 6.
 *
 * # Why a constructor-injected event bus
 *
 * `releaseCamera` + `freeze` (auto-release at the end of the window) publish
 * `CameraReleased`. The bus is constructor-injected so tests can pass a
 * fake bus and assert event ordering.
 *
 * In-memory `freeze` does **not** schedule an auto-release tick (no
 * `IScheduler` dependency injected here to avoid the lifetime question of
 * "what if the gateway is replaced before the scheduled task fires?"). Tests
 * that want to assert the auto-release behaviour call `releaseCamera`
 * explicitly after the freeze window. The Paper adapter wires
 * `IScheduler.run(durationTicks) { releaseCamera(playerId) }`.
 */
public class InMemoryCameraGateway(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : ICameraGateway {

    private val active: ConcurrentHashMap<PlayerId, CameraHandle> = ConcurrentHashMap()

    override fun lockCamera(
        playerId: PlayerId,
        target: CameraTarget,
    ): Result<CameraHandle, CameraError> {
        val handle = mintHandle()
        active[playerId] = handle
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
        val handle = mintHandle()
        active[playerId] = handle
        return Result.success(handle)
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
        // No state change for shake in the in-memory impl — it's a one-shot
        // effect with no persistent handle. The Paper adapter (Wave 6) tracks
        // the active shake via its own scheduler tick.
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
        return Result.success(Unit)
    }

    override fun releaseCamera(playerId: PlayerId): Result<Unit, CameraError> {
        val handle = active.remove(playerId)
        if (handle != null) {
            eventBus.publish(
                CameraReleased(
                    playerId = playerId,
                    handle = handle,
                    atTick = tickClock(),
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
        val handle = mintHandle()
        active[playerId] = handle
        return Result.success(handle)
    }

    override fun activeHandle(playerId: PlayerId): CameraHandle? = active[playerId]

    private fun mintHandle(): CameraHandle = CameraHandle(UUID.randomUUID().toString())
}
