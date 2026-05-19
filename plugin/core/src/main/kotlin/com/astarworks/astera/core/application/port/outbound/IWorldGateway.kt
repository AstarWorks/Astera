package com.astarworks.astera.core.application.port.outbound

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
 * Phase 2 mid lands block/effect implementations; Wave 5 (Paper runtime)
 * lands [teleport]. Ports are declared now so use cases written against
 * them can be unit-tested with `FakeWorldGateway` (test-fixtures) ahead of
 * the Paper plumbing.
 */
public interface IWorldGateway {

    public fun placeBlock(world: WorldId, at: Vec3, type: BlockType)
    public fun removeBlock(world: WorldId, at: Vec3)
    public fun blockAt(world: WorldId, at: Vec3): BlockType?

    public fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec)
    public fun playSound(world: WorldId, at: Vec3, sound: SoundSpec)

    /**
     * Teleport [playerId] to [at] in [world] (or the player's current world
     * if [world] is null). Returns true on success, false if the player is
     * offline or the target world is unknown.
     *
     * Wave 4.5 (R3.5, ADR-0027): ports + Fake impl land here; Paper-side
     * impl lands in Wave 5 (R4 Paper runtime). Until then, MatchSpawnUseCase
     * exercises the port through FakeWorldGateway only.
     */
    public fun teleport(playerId: PlayerId, at: Vec3, world: WorldId? = null): Boolean

    /**
     * Teleport [playerId] to the spectator spawn for [matchId] (Wave 5h,
     * ADR-0074 §6). Used by `EnterSpectatorUseCase` to drop a player at a
     * match-defined vantage point (e.g. above the arena center).
     *
     * Returns [WorldGatewayError.UnknownMatch] if no spectator spawn is
     * registered for [matchId], [WorldGatewayError.UnknownPlayer] if the
     * target is offline, or [WorldGatewayError.Storage] on backend failure.
     */
    public fun spectatorSpawn(matchId: MatchId, playerId: PlayerId): Result<Unit, WorldGatewayError>

    /**
     * Teleport [playerId] to the lobby spawn (Wave 5h, ADR-0074 §6). Used by
     * `LeaveSpectatorUseCase` and the reconnect-timeout fallback when a
     * disconnected player exceeds the 3-min ACTIVE reconnect window.
     *
     * The lobby spawn is server-global; Paper impl (Wave 6b) reads it from
     * `world.spawnLocation` or a configured lobby world.
     */
    public fun lobbySpawn(playerId: PlayerId): Result<Unit, WorldGatewayError>
}

/** Sealed failure surface for the spawn / teleport methods added in Wave 5h. */
public sealed class WorldGatewayError {
    public data class UnknownMatch(public val matchId: MatchId) : WorldGatewayError()
    public data class UnknownPlayer(public val playerId: PlayerId) : WorldGatewayError()
    public data class Storage(public val detail: String) : WorldGatewayError()
}
