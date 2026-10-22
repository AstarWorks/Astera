package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe in-memory [IPlayerProfileRepository]; the Postgres-less fallback. */
public class InMemoryPlayerProfileRepository : IPlayerProfileRepository {
    private val profiles: ConcurrentHashMap<PlayerId, PlayerProfile> = ConcurrentHashMap()

    override fun find(playerId: PlayerId): PlayerProfile? = profiles[playerId]

    override fun insertIfAbsent(profile: PlayerProfile): Boolean =
        profiles.putIfAbsent(profile.id, profile) == null
}
