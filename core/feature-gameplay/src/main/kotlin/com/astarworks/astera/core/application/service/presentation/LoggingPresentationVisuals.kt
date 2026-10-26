package com.astarworks.astera.core.application.service.presentation

import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.ParticlePreset
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.CopyOnWriteArrayList

/** Headless [IPresentationVisuals]: records what would be shown (hosts without a client, and game-sim). */
public class LoggingPresentationVisuals : IPresentationVisuals {
    public sealed interface Call {
        public data class Title(val player: PlayerId, val title: MessageKey?, val subtitle: MessageKey?, val args: Map<String, String>) : Call
        public data class ActionBar(val player: PlayerId, val key: MessageKey, val args: Map<String, String>) : Call
        public data class Particles(val preset: ParticlePreset, val viewers: List<PlayerId>, val anchor: PlayerId, val at: Vec3?) : Call
    }

    public val calls: MutableList<Call> = CopyOnWriteArrayList()

    override fun title(player: PlayerId, title: MessageKey?, subtitle: MessageKey?, args: Map<String, String>, stayTicks: Int) {
        calls += Call.Title(player, title, subtitle, args)
    }

    override fun actionBar(player: PlayerId, key: MessageKey, args: Map<String, String>) {
        calls += Call.ActionBar(player, key, args)
    }

    override fun particles(preset: ParticlePreset, viewers: Collection<PlayerId>, anchor: PlayerId, at: Vec3?, world: WorldId?) {
        calls += Call.Particles(preset, viewers.toList(), anchor, at)
    }
}
