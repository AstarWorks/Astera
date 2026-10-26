package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.port.outbound.IActionBarChannel
import com.astarworks.astera.core.application.port.outbound.postRendered
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.kyori.adventure.util.Ticks
import org.bukkit.Server
import java.util.concurrent.ThreadLocalRandom

/**
 * Paper concrete of [IPlayerFeedbackGateway] (WP4): Adventure action bar and
 * title, with the same MiniMessage deserialization as chat (`PaperPlayer`).
 * Main thread only; offline players are skipped.
 */
public class PaperPlayerFeedbackGateway(
    private val server: Server,
    private val actionBar: IActionBarChannel,
) : IPlayerFeedbackGateway {

    override fun postActionBar(target: PlayerId, rendered: String) {
        actionBar.postRendered(target, rendered)
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

    override fun sendScreenFlash(
        target: PlayerId,
        overlay: ScreenOverlay,
        colorRgb: Int,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    ) {
        val player = server.getPlayer(target.uuid) ?: return
        val glyph = Component.text(overlay.glyph.toString())
            .font(Key.key(ScreenOverlay.FONT_NAMESPACE, ScreenOverlay.FONT_PATH))
            .color(TextColor.color(colorRgb))
        player.showTitle(
            Title.title(
                glyph,
                Component.empty(),
                Title.Times.times(
                    Ticks.duration(fadeInTicks.toLong()),
                    Ticks.duration(stayTicks.toLong()),
                    Ticks.duration(fadeOutTicks.toLong()),
                ),
            ),
        )
    }

    override fun cameraKick(target: PlayerId, intensity: Double) {
        // Approximation of a screen shake: a small random view nudge per call.
        val player = server.getPlayer(target.uuid) ?: return
        val rng = ThreadLocalRandom.current()
        val spread = intensity.coerceIn(0.0, 1.0) * MAX_JITTER_DEGREES
        val loc = player.location
        val yaw = loc.yaw + ((rng.nextDouble() * 2.0 - 1.0) * spread).toFloat()
        val pitch = (loc.pitch + ((rng.nextDouble() * 2.0 - 1.0) * spread).toFloat()).coerceIn(-90f, 90f)
        player.setRotation(yaw, pitch)
    }

    private companion object {
        /** View jitter (degrees, per axis) at full intensity. */
        const val MAX_JITTER_DEGREES: Double = 4.0
    }
}
