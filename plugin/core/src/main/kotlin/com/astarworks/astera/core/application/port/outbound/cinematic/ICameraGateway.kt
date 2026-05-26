package com.astarworks.astera.core.application.port.outbound.cinematic

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.cinematic.CameraHandle
import com.astarworks.astera.core.domain.cinematic.CameraTarget
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: player camera control (Wave 5cinematic, ADR-0110).
 *
 * Wave 5cinematic ships `InMemoryCameraGateway` + `FakeCameraGateway`.
 * Wave 6 lands `PaperCameraGateway` over Paper packet primitives
 * (`CameraEntity` for entity-following, `PlayerTeleportEvent` + spectator-mode
 * proxy for raw position lock).
 *
 * Vendor-neutral by construction: no Bukkit / packet types in the port; the
 * adapter unwraps [CameraTarget] variants.
 *
 * # Easing reuse
 *
 * [panCamera] takes the Wave 5h [Easing] enum (32 standard curves). This
 * wave does **NOT** introduce a new easing curve.
 *
 * # Concurrency & ownership
 *
 * Each player has at most one active camera handle. `lockCamera` returns a
 * new handle; calling `lockCamera` again on the same player implicitly
 * releases the previous one (no event for the implicit release — `releaseCamera`
 * is the explicit signal that fires `CameraReleased`).
 */
public interface ICameraGateway {

    /**
     * Lock the player's camera onto [target]. Returns an opaque handle the
     * caller uses for subsequent operations on this lock. Implicitly releases
     * any prior lock for the same player.
     */
    public fun lockCamera(
        playerId: PlayerId,
        target: CameraTarget,
    ): Result<CameraHandle, CameraError>

    /**
     * Pan the camera from one target to another over [durationTicks] with the
     * given easing curve. The lock acquired here is a new handle distinct
     * from any prior `lockCamera` handle (callers may discard the prior
     * handle).
     */
    public fun panCamera(
        playerId: PlayerId,
        fromTarget: CameraTarget,
        toTarget: CameraTarget,
        durationTicks: Long,
        easing: Easing,
    ): Result<CameraHandle, CameraError>

    /**
     * Apply screen shake to the player's view for [durationTicks].
     * [intensity] is in `0.0..1.0` (0 = no shake, 1 = full shake).
     */
    public fun shakeCamera(
        playerId: PlayerId,
        intensity: Double,
        durationTicks: Long,
    ): Result<Unit, CameraError>

    /**
     * Multiply the player's FOV by [fovMultiplier] over [durationTicks].
     * Multiplier `1.0` = no change. `> 1.0` = wider FOV; `< 1.0` = zoom in.
     */
    public fun zoomCamera(
        playerId: PlayerId,
        fovMultiplier: Double,
        durationTicks: Long,
    ): Result<Unit, CameraError>

    /**
     * Release the current camera lock for [playerId]. Idempotent: releasing
     * an unlocked player returns Success. Fires [com.astarworks.astera.core.domain.event.CameraReleased].
     */
    public fun releaseCamera(playerId: PlayerId): Result<Unit, CameraError>

    /**
     * Freeze the player's camera at its current position for [durationTicks]
     * (no rotation, no movement). Used for cutscene black-screen freezes
     * where the player should not be able to look around.
     *
     * Auto-releases at the end of the window (fires `CameraReleased`).
     */
    public fun freeze(
        playerId: PlayerId,
        durationTicks: Long,
    ): Result<CameraHandle, CameraError>

    /**
     * Current active camera handle for [playerId], or `null` if no lock is
     * active. Used by HUD / debug commands.
     */
    public fun activeHandle(playerId: PlayerId): CameraHandle?
}

public sealed class CameraError {
    public data class UnknownPlayer(public val playerId: PlayerId) : CameraError()
    public data class InvalidIntensity(public val detail: String) : CameraError()
    public data class InvalidZoom(public val detail: String) : CameraError()
    public data class InvalidDuration(public val detail: String) : CameraError()
    public data class Platform(public val detail: String) : CameraError()
}
