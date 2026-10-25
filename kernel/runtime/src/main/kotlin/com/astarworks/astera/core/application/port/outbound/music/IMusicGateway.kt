package com.astarworks.astera.core.application.port.outbound.music

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.music.MusicHandle
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: long-form music playback per player with cross-fade +
 * ducking (Wave 5cinematic, ADR-0110).
 *
 * Wave 5cinematic ships `InMemoryMusicGateway` + `FakeMusicGateway`.
 * Wave 6 lands `PaperMusicGateway` over Paper's `Player.playSound` +
 * resource pack OGG primitives.
 *
 * # Why a separate port from `playSound`
 *
 *  - Short SFX (sword swing) is fire-and-forget — no handle, no cross-fade,
 *    no ducking. Handled by [com.astarworks.astera.core.application.port.outbound.IBroadcaster]-style
 *    helpers in feature plugins.
 *  - Long-form music (match BGM, boss theme, ambient lobby) needs
 *    cross-fade (track A volume↓ + track B volume↑), ducking (BGM
 *    temporarily quieter while voice-over speaks), and explicit stop —
 *    all of which require a handle.
 *
 * # Concurrency
 *
 * A player may have multiple concurrent music handles (overlay sting on
 * top of looping BGM). The Paper adapter (Wave 6) mixes them client-side
 * via resource-pack sound categories.
 */
public interface IMusicGateway {

    /**
     * Begin playing [track] for [playerId] at [volume] (`0.0..1.0`,
     * multiplied by `MusicTrack.defaultVolume` at the adapter layer).
     */
    public fun play(
        playerId: PlayerId,
        track: MusicTrackId,
        volume: Double,
    ): Result<MusicHandle, MusicError>

    /**
     * Cross-fade from [fromHandle] to a newly-started [toTrack] over
     * [durationTicks]. Returns the new handle. The old handle is implicitly
     * stopped at the end of the cross-fade window.
     */
    public fun crossfade(
        fromHandle: MusicHandle,
        toTrack: MusicTrackId,
        durationTicks: Long,
    ): Result<MusicHandle, MusicError>

    /**
     * Reduce the volume of [handle] by [factor] over [durationTicks] (BGM
     * ducking so voice-over can speak over it). `factor` is `0.0..1.0` —
     * `0.0` is silent, `1.0` is no change. The duck applies until [unduck]
     * is called or the handle stops.
     */
    public fun duck(
        handle: MusicHandle,
        factor: Double,
        durationTicks: Long,
    ): Result<Unit, MusicError>

    /**
     * Restore [handle] to its original volume over [durationTicks].
     * Idempotent — un-ducking a non-ducked handle returns Success.
     */
    public fun unduck(
        handle: MusicHandle,
        durationTicks: Long,
    ): Result<Unit, MusicError>

    /**
     * Stop [handle] with a [fadeOutTicks]-long fade. `fadeOutTicks = 0`
     * stops immediately. Idempotent — stopping an already-stopped handle
     * returns Success.
     */
    public fun stop(
        handle: MusicHandle,
        fadeOutTicks: Long,
    ): Result<Unit, MusicError>

    /** Snapshot of currently-active music handles for [playerId]. */
    public fun active(playerId: PlayerId): Collection<MusicHandle>
}

public sealed class MusicError {
    public data class UnknownPlayer(public val playerId: PlayerId) : MusicError()
    public data class UnknownHandle(public val handle: MusicHandle) : MusicError()
    public data class UnknownTrack(public val trackId: MusicTrackId) : MusicError()
    public data class InvalidVolume(public val detail: String) : MusicError()
    public data class InvalidDuration(public val detail: String) : MusicError()
    public data class Platform(public val detail: String) : MusicError()
}
