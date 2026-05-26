package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.application.port.outbound.cinematic.FreelookError
import com.astarworks.astera.core.application.port.outbound.cinematic.IFreelookGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cinematic.FreelookSession
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IFreelookGateway] (Wave 5cinematic, ADR-0110).
 *
 * Tracks the active session per player. Production Paper adapter (Wave 6)
 * additionally hooks the Paper movement listener to cancel any non-look
 * input while the session is active.
 */
public class InMemoryFreelookGateway : IFreelookGateway {

    private val sessions: ConcurrentHashMap<PlayerId, FreelookSession> = ConcurrentHashMap()

    override fun enableFreelook(
        playerId: PlayerId,
        anchorPosition: Vec3,
    ): Result<FreelookSession, FreelookError> {
        val session = FreelookSession(playerId, anchorPosition)
        sessions[playerId] = session
        return Result.success(session)
    }

    override fun disableFreelook(playerId: PlayerId): Result<Unit, FreelookError> {
        sessions.remove(playerId)
        return Result.success(Unit)
    }

    override fun session(playerId: PlayerId): FreelookSession? = sessions[playerId]

    override fun all(): Collection<FreelookSession> = sessions.values.toList()
}
