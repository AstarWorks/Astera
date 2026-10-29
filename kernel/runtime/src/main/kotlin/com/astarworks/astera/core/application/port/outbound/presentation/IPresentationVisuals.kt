package com.astarworks.astera.core.application.port.outbound.presentation

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.ParticlePreset
import com.astarworks.astera.core.domain.world.WorldId

/** Outbound: the visual half of a presentation (Paper: titles, action bar, per-viewer particles). */
public interface IPresentationVisuals {
    public fun title(player: PlayerId, title: MessageKey?, subtitle: MessageKey?, args: Map<String, String>, stayTicks: Int)

    public fun chat(player: PlayerId, key: MessageKey, args: Map<String, String>)

    public fun actionBar(player: PlayerId, key: MessageKey, args: Map<String, String>)

    /** Spawns [preset] for each of [viewers] only, centred on [at] (or above [anchor] when [at] is null). */
    public fun particles(preset: ParticlePreset, viewers: Collection<PlayerId>, anchor: PlayerId, at: Vec3?, world: WorldId?)
}
