package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings

/**
 * Outbound port: per-player typed settings (Wave 5e, RTM issue #15).
 *
 * Backed by V8 `player_settings` (player_id PK, settings JSONB). Sealed
 * variants in [PlayerSettings] (e.g. [ControlScheme.Button]) round-trip
 * through the JSONB codec; the InMemory default returns the configured
 * default when no row exists.
 */
public interface IPlayerSettingsRepository {
    public fun find(playerId: PlayerId): PlayerSettings?
    public fun upsert(settings: PlayerSettings)

    /** Convenience: returns [PlayerSettings.default] if no row exists. */
    public fun findOrDefault(playerId: PlayerId): PlayerSettings =
        find(playerId) ?: PlayerSettings.default(playerId)
}
