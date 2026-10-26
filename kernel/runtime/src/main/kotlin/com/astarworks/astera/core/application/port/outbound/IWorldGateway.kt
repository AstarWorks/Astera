package com.astarworks.astera.core.application.port.outbound

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: side-effecting operations on the world (blocks, effects,
 * teleports).
 *
 * Phrased entirely in domain types — [Vec3], [BlockType], [WorldId],
 * [ParticleSpec], [SoundSpec], [PlayerId]. The Paper adapter resolves these
 * to Bukkit `Location` / `Material` / `Particle` / `Sound` / `Player`.
 *
 * Teleport futures complete only after the move succeeds or fails. Runtime bindings dispatch
 * completion on the entity owner so dependent world/player operations can safely continue.
 */
public interface IWorldGateway {

    /** World writes dispatch to the affected chunk; zero-delay writes on its owner run inline. */
    public fun placeBlock(world: WorldId, at: Vec3, type: BlockType)
    public fun removeBlock(world: WorldId, at: Vec3)
    /** Synchronous live read: callers must already own the affected chunk. */
    public fun blockAt(world: WorldId, at: Vec3): BlockType?

    public fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec)
    public fun playSound(world: WorldId, at: Vec3, sound: SoundSpec)

    /**
     * Teleport [playerId] to [at] in [world] (or the player's current world
     * if [world] is null). [yaw]/[pitch] set the facing; null keeps the
     * player's current value. Completes with true on success, false for an offline
     * player, unknown target world, cancellation or failed teleport.
     *
     */
    public fun teleportAsync(
        playerId: PlayerId,
        at: Vec3,
        world: WorldId? = null,
        yaw: Float? = null,
        pitch: Float? = null,
    ): CompletableFuture<Boolean>

    /**
     * Teleport [playerId] to the spectator spawn for [matchId] (Wave 5h,
     * ADR-0079 §6). Used by `EnterSpectatorUseCase` to drop a player at a
     * match-defined vantage point (e.g. above the arena center).
     *
     * Returns [WorldGatewayError.UnknownMatch] if no spectator spawn is
     * registered for [matchId], [WorldGatewayError.UnknownPlayer] if the
     * target is offline, or [WorldGatewayError.Storage] on backend failure.
     */
    public fun spectatorSpawn(matchId: MatchId, playerId: PlayerId): CompletableFuture<Result<Unit, WorldGatewayError>>

    /**
     * Teleport [playerId] to the lobby spawn (Wave 5h, ADR-0079 §6). Used by
     * `LeaveSpectatorUseCase` and the reconnect-timeout fallback when a
     * disconnected player exceeds the 3-min ACTIVE reconnect window.
     *
     * The lobby spawn is server-global; Paper impl (Wave 6b) reads it from
     * `world.spawnLocation` or a configured lobby world.
     */
    public fun lobbySpawn(playerId: PlayerId): CompletableFuture<Result<Unit, WorldGatewayError>>
}

/** Sealed failure surface for the spawn / teleport methods added in Wave 5h. */
public sealed class WorldGatewayError {
    public data class UnknownMatch(public val matchId: MatchId) : WorldGatewayError()
    public data class UnknownPlayer(public val playerId: PlayerId) : WorldGatewayError()
    public data class Storage(public val detail: String) : WorldGatewayError()
}
