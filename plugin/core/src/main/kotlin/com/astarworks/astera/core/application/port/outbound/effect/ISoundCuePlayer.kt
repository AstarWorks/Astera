package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * The single way game code plays a milestone sound (ADR-0256): name a cue id defined in
 * YAML under `content/sounds/`; the player resolves its layers, applies the listener's sound volume
 * setting (0 = muted) and sends them through [IPlayerSoundGateway]. Nothing outside the Paper
 * gateway adapter should build a raw sound key.
 */
public interface ISoundCuePlayer {
    /** Plays [cue] for [playerId] only ([pitchScale] multiplies every layer's pitch, clamped to the valid range). Returns `false` when the cue is unknown or muted for that player. */
    public fun play(playerId: PlayerId, cue: SoundId, position: Vec3? = null, world: WorldId? = null, pitchScale: Double = 1.0): Boolean

    /** Plays [cue] for each of [players] (each applies their own volume setting). */
    public fun playAll(players: Iterable<PlayerId>, cue: SoundId, position: Vec3? = null, world: WorldId? = null)
}
