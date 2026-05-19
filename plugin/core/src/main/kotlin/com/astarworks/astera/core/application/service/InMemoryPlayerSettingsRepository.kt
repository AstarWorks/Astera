package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [IPlayerSettingsRepository] (Wave
 * 5e, RTM issue #15).
 *
 * Persistence's `PostgresPlayerSettingsRepository` overlays this at priority
 * 100 in production. The default port-level [findOrDefault] reads
 * [PlayerSettings.default] when no row exists, so this class only implements
 * the raw [find] + [upsert].
 */
public class InMemoryPlayerSettingsRepository : IPlayerSettingsRepository {

    private val rows: ConcurrentHashMap<PlayerId, PlayerSettings> = ConcurrentHashMap()

    override fun find(playerId: PlayerId): PlayerSettings? = rows[playerId]

    override fun upsert(settings: PlayerSettings) {
        rows[settings.playerId] = settings
    }
}
