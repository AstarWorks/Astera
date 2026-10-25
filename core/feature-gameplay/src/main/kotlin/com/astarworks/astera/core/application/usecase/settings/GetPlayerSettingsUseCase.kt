package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings

/**
 * Returns the player's settings, falling back to
 * [PlayerSettings.default] when no row exists (Wave 5e, RTM issue #15).
 *
 * Reads are always successful — defaults are baked into the domain — so the
 * use case returns [PlayerSettings] directly rather than `Result`.
 */
public class GetPlayerSettingsUseCase(private val settings: IPlayerSettingsRepository) {

    public fun get(playerId: PlayerId): PlayerSettings = settings.findOrDefault(playerId)
}
