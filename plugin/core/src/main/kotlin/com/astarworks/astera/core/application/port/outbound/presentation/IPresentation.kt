package com.astarworks.astera.core.application.port.outbound.presentation

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * The one way game code shows a milestone (FX-05): name a moment from `content/presentation/events.yaml`; the table
 * decides the sound cue (ADR-0256), title / subtitle / action bar, and particle preset. Particles honour each
 * viewer's particle-visibility setting.
 */
public interface IPresentation {
    /**
     * Plays [moment] for [subject] (cue, title, subtitle, action bar) and for [audience] (the entry's audience cue,
     * heard at [at] when given). The particle preset is shown to [subject] plus [audience] at [at] (default: at the
     * subject). Returns `false` when the moment has no table row.
     */
    public fun play(
        moment: String,
        subject: PlayerId,
        args: Map<String, String> = emptyMap(),
        at: Vec3? = null,
        world: WorldId? = null,
        audience: Collection<PlayerId> = emptyList(),
    ): Boolean
}
