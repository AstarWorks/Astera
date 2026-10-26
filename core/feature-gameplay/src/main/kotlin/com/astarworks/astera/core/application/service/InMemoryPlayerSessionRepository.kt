package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [IPlayerSessionRepository] (Wave 5e).
 *
 * Sessions are runtime-only — no Postgres overlay. They are reconstructed at
 * `PlayerJoined` from [com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository]
 * and lobby defaults. Wave 6b's `BukkitEventAdapter` observes
 * [com.astarworks.astera.core.domain.player.SessionState.Loading] here to gate
 * inventory-action events while a restore is in progress.
 */
public class InMemoryPlayerSessionRepository : IPlayerSessionRepository {

    private val sessions: ConcurrentHashMap<PlayerId, PlayerSession> = ConcurrentHashMap()

    override fun find(playerId: PlayerId): PlayerSession? = sessions[playerId]

    override fun upsert(session: PlayerSession) {
        sessions[session.playerId] = session
    }

    override fun remove(playerId: PlayerId) {
        sessions.remove(playerId)
    }

    override fun all(): List<PlayerSession> = sessions.values.toList()
}
