package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: play a sound for a specific viewer only.
 *
 * Wave 5effects (ADR-0109). Complements `IWorldGateway.playSound` (which
 * emits to every player in range): this variant routes through
 * Bukkit's `Player.playSound(...)` so only the target hears it.
 *
 * # Why dedicated
 *
 * Team-only cues (ally radar ping, "your skill is off cooldown"
 * confirmation, betting-match private countdown) must not leak to
 * opponents. Without this port, callers would have to instantiate the
 * full `IPacketInterceptor` machinery from Wave 5y just to scope a
 * single sound — overkill and brittle.
 *
 * # Wiring
 *
 *  - In-memory default (`LoggingPlayerSoundGateway`) registered at
 *    priority 0; warn-logs every call so missing Paper impl surfaces.
 *  - Paper adapter (Wave 6) registers `PaperPlayerSoundGateway` at
 *    priority 100; routes through `Player.playSound(Location, Sound, ...)`.
 *  - Tests use `FakePlayerSoundGateway`.
 */
public interface IPlayerSoundGateway {

    /**
     * Play [sound] for [playerId] only.
     *
     *  - [position] is the spatial origin. If `null` the sound is played
     *    at the player's current location (the adapter resolves via
     *    `IPlayerGateway`).
     *  - [world] is the target world; `null` defers to the player's
     *    current world (same convention as `IWorldGateway.teleport`).
     */
    public fun playForViewer(
        playerId: PlayerId,
        sound: SoundSpec,
        position: Vec3? = null,
        world: WorldId? = null,
    )

    /** Stops the sound [key] (`namespace:name`, as in [SoundSpec.key]) for [playerId] only. Default: no-op. */
    public fun stop(playerId: PlayerId, key: String) {}
}
