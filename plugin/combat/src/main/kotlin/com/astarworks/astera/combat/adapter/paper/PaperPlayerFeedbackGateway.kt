package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.kyori.adventure.util.Ticks
import org.bukkit.Server

/**
 * Paper concrete of [IPlayerFeedbackGateway] (WP4): Adventure action bar and
 * title, with the same MiniMessage deserialization as chat (`PaperPlayer`).
 * Main thread only; offline players are skipped.
 */
public class PaperPlayerFeedbackGateway(
    private val server: Server,
) : IPlayerFeedbackGateway {

    override fun sendActionBar(target: PlayerId, rendered: String) {
        server.getPlayer(target.uuid)?.sendActionBar(AsteraTextDeserializer.deserialize(rendered))
    }

    override fun sendTitle(
        target: PlayerId,
        title: String,
        subtitle: String?,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    ) {
        val player = server.getPlayer(target.uuid) ?: return
        player.showTitle(
            Title.title(
                AsteraTextDeserializer.deserialize(title),
                subtitle?.let { AsteraTextDeserializer.deserialize(it) } ?: Component.empty(),
                Title.Times.times(
                    Ticks.duration(fadeInTicks.toLong()),
                    Ticks.duration(stayTicks.toLong()),
                    Ticks.duration(fadeOutTicks.toLong()),
                ),
            ),
        )
    }
}
