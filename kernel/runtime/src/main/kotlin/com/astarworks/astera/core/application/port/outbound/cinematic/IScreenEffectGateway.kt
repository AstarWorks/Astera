package com.astarworks.astera.core.application.port.outbound.cinematic

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cinematic.Color
import com.astarworks.astera.core.domain.cinematic.ScreenEffectHandle
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: screen-space overlay effects (Wave 5cinematic, ADR-0110).
 *
 * Two primitives:
 *
 *  - [fade] — interpolate a translucent color overlay from [fromAlpha] to
 *    [toAlpha] over [durationTicks]. The standard cutscene fade-in/out
 *    (typically `fade(player, 0.0, 1.0, 20, BLACK)` then a held black screen
 *    then `fade(player, 1.0, 0.0, 20, BLACK)`).
 *
 *  - [freeze] — snapshot the player's current screen and hold it static for
 *    [durationTicks]. Used for "everyone froze" hit-stop on huge damage
 *    hits. Wave 6 Paper impl likely uses Display-entity overlays + input
 *    capture.
 *
 * Wave 5cinematic ships `InMemoryScreenEffectGateway` + `FakeScreenEffectGateway`.
 */
public interface IScreenEffectGateway {

    /**
     * Fade the player's screen between alpha values over [durationTicks]
     * with [color] as the overlay tint. Alpha is `0.0..1.0`.
     */
    public fun fade(
        playerId: PlayerId,
        fromAlpha: Double,
        toAlpha: Double,
        durationTicks: Long,
        color: Color,
    ): Result<ScreenEffectHandle, ScreenEffectError>

    /**
     * Freeze the player's screen for [durationTicks]. The Paper adapter
     * snapshots the last-rendered frame; the player cannot look around
     * during the freeze.
     */
    public fun freeze(
        playerId: PlayerId,
        durationTicks: Long,
    ): Result<ScreenEffectHandle, ScreenEffectError>

    /**
     * Cancel an in-flight screen effect. Idempotent — cancelling an unknown
     * or already-completed handle returns Success.
     */
    public fun cancel(handle: ScreenEffectHandle): Result<Unit, ScreenEffectError>

    /** Snapshot of currently active effect handles for [playerId]. */
    public fun active(playerId: PlayerId): Collection<ScreenEffectHandle>
}

public sealed class ScreenEffectError {
    public data class UnknownPlayer(public val playerId: PlayerId) : ScreenEffectError()
    public data class InvalidAlpha(public val detail: String) : ScreenEffectError()
    public data class InvalidDuration(public val detail: String) : ScreenEffectError()
    public data class Platform(public val detail: String) : ScreenEffectError()
}
