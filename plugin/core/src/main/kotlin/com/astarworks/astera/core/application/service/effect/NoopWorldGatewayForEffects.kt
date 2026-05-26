package com.astarworks.astera.core.application.service.effect

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
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Fallback [IWorldGateway] used by the Wave 5effects schedulers when no
 * Paper-backed gateway is registered yet (pre-Wave 6).
 *
 * Wave 5effects (ADR-0109). Drops every spawn/play call with a debug log
 * so authoring loops don't crash before the Paper adapter lands.
 *
 * Lives in `core.application.service.effect.*` (not `core.adapter.paper.*`)
 * because it deliberately contains zero Bukkit imports — it is a domain-
 * level inert placeholder, not a vendor adapter. Once Wave 6b registers
 * `PaperWorldGateway` at priority 100 this placeholder is shadowed by the
 * service registry priority lookup and never invoked again.
 */
internal class NoopWorldGatewayForEffects(
    private val log: Logger = LoggerFactory.getLogger(NoopWorldGatewayForEffects::class.java),
) : IWorldGateway {

    override fun placeBlock(world: WorldId, at: Vec3, type: BlockType) {
        log.debug("noop placeBlock({}, {}, {})", world, at, type)
    }

    override fun removeBlock(world: WorldId, at: Vec3) {
        log.debug("noop removeBlock({}, {})", world, at)
    }

    override fun blockAt(world: WorldId, at: Vec3): BlockType? = null

    override fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec) {
        log.debug("noop spawnParticle({}, {}, key={})", world, at, particle.key)
    }

    override fun playSound(world: WorldId, at: Vec3, sound: SoundSpec) {
        log.debug("noop playSound({}, {}, key={})", world, at, sound.key)
    }

    override fun teleport(playerId: PlayerId, at: Vec3, world: WorldId?): Boolean {
        log.debug("noop teleport({}, {}, {})", playerId, at, world)
        return false
    }

    override fun spectatorSpawn(matchId: MatchId, playerId: PlayerId): Result<Unit, WorldGatewayError> {
        log.debug("noop spectatorSpawn({}, {})", matchId, playerId)
        return Result.Failure(WorldGatewayError.UnknownMatch(matchId))
    }

    override fun lobbySpawn(playerId: PlayerId): Result<Unit, WorldGatewayError> {
        log.debug("noop lobbySpawn({})", playerId)
        return Result.Failure(WorldGatewayError.UnknownPlayer(playerId))
    }
}
