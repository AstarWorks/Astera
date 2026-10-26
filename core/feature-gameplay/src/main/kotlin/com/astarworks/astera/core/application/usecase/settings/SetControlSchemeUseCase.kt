package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.ControlScheme
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Switches the player's [ControlScheme] (Wave 5e, RTM issue #15).
 *
 * Reads the current settings (or defaults), `copy`s the field, then upserts.
 * Wave 6a's input adapter reads the saved [ControlScheme] when binding skill
 * triggers.
 */
public class SetControlSchemeUseCase(private val settings: IPlayerSettingsRepository) {

    public fun set(playerId: PlayerId, scheme: ControlScheme) {
        val current = settings.findOrDefault(playerId)
        settings.upsert(current.copy(controlScheme = scheme))
    }
}
