package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Switches the per-viewer particle filter (Wave 5e, RTM issue #15).
 *
 * Wave 6a's packet filter on `Server#spawnParticle` reads the saved
 * [ParticleVisibility] before deciding to suppress particles for a viewer
 * (e.g. only show particles from the viewer's own team when
 * [ParticleVisibility.OwnTeamOnly]).
 */
public class SetParticleVisibilityUseCase(private val settings: IPlayerSettingsRepository) {

    public fun set(playerId: PlayerId, visibility: ParticleVisibility) {
        val current = settings.findOrDefault(playerId)
        settings.upsert(current.copy(particleVisibility = visibility))
    }
}
