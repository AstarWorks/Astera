package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.WorldGatewayError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * In-memory [IWorldGateway] for tests. Each operation records its arguments
 * for assertion. Block state is kept in a single map keyed by (world, position).
 */
public class FakeWorldGateway : IWorldGateway {

    public data class Placement(val world: WorldId, val at: Vec3, val type: BlockType)
    public data class Removal(val world: WorldId, val at: Vec3)
    public data class ParticleEmission(val world: WorldId, val at: Vec3, val particle: ParticleSpec)
    public data class SoundEmission(val world: WorldId, val at: Vec3, val sound: SoundSpec)
    public data class TeleportCall(val playerId: PlayerId, val at: Vec3, val world: WorldId?)

    /**
     * Wave 5h: records a [spectatorSpawn] / [lobbySpawn] call. The [target]
     * string is `"match:<matchId>"` for spectator-spawn and `"lobby"` for
     * lobby-spawn — keeps a single recording list while still letting tests
     * disambiguate the two variants.
     */
    public data class SpawnCall(val playerId: PlayerId, val target: String)

    public val placements: MutableList<Placement> = mutableListOf()
    public val removals: MutableList<Removal> = mutableListOf()
    public val particles: MutableList<ParticleEmission> = mutableListOf()
    public val sounds: MutableList<SoundEmission> = mutableListOf()
    public val teleports: MutableList<TeleportCall> = mutableListOf()

    /** Wave 5h: records every spectatorSpawn / lobbySpawn invocation in call order. */
    public val spawnCalls: MutableList<SpawnCall> = mutableListOf()

    /** When non-null, [teleport] returns this value instead of true. Use for failure-path tests. */
    public var teleportResult: Boolean = true

    /**
     * When non-null, [spectatorSpawn] returns this value instead of success.
     * Use for failure-path tests (e.g. simulate UnknownMatch).
     */
    public var spectatorSpawnResult: Result<Unit, WorldGatewayError>? = null

    /**
     * When non-null, [lobbySpawn] returns this value instead of success.
     * Use for failure-path tests (e.g. simulate Storage failure).
     */
    public var lobbySpawnResult: Result<Unit, WorldGatewayError>? = null

    private val blocks: MutableMap<Pair<WorldId, Vec3>, BlockType> = mutableMapOf()

    override fun placeBlock(world: WorldId, at: Vec3, type: BlockType) {
        blocks[world to at] = type
        placements += Placement(world, at, type)
    }

    override fun removeBlock(world: WorldId, at: Vec3) {
        blocks.remove(world to at)
        removals += Removal(world, at)
    }

    override fun blockAt(world: WorldId, at: Vec3): BlockType? = blocks[world to at]

    override fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec) {
        particles += ParticleEmission(world, at, particle)
    }

    override fun playSound(world: WorldId, at: Vec3, sound: SoundSpec) {
        sounds += SoundEmission(world, at, sound)
    }

    override fun teleport(playerId: PlayerId, at: Vec3, world: WorldId?): Boolean {
        teleports += TeleportCall(playerId, at, world)
        return teleportResult
    }

    override fun spectatorSpawn(matchId: MatchId, playerId: PlayerId): Result<Unit, WorldGatewayError> {
        spawnCalls += SpawnCall(playerId, "match:${matchId.value}")
        return spectatorSpawnResult ?: Result.success(Unit)
    }

    override fun lobbySpawn(playerId: PlayerId): Result<Unit, WorldGatewayError> {
        spawnCalls += SpawnCall(playerId, "lobby")
        return lobbySpawnResult ?: Result.success(Unit)
    }
}
