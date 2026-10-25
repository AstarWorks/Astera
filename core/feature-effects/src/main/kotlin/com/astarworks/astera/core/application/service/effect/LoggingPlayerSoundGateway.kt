package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Default no-op [IPlayerSoundGateway] (Wave 5effects / ADR-0109).
 *
 * Logs at `debug` and drops the call. Registered at priority 0 by
 * `AsteraCorePlugin` so the orchestrator never NPEs before the Paper
 * adapter (Wave 6) registers a real impl at priority 100.
 */
public class LoggingPlayerSoundGateway(
    private val log: Logger = LoggerFactory.getLogger(LoggingPlayerSoundGateway::class.java),
) : IPlayerSoundGateway {

    override fun playForViewer(playerId: PlayerId, sound: SoundSpec, position: Vec3?, world: WorldId?) {
        log.debug(
            "playForViewer (player={}, sound={}, pos={}, world={}) — default no-op (Wave 6 Paper impl pending)",
            playerId, sound.key, position, world,
        )
    }
}
