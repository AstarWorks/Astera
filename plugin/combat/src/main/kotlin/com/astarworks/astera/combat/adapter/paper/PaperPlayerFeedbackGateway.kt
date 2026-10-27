package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.application.port.outbound.IActionBarChannel
import com.astarworks.astera.core.application.port.outbound.postRendered
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay
import io.papermc.paper.math.Angle
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
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

    override fun cameraKick(target: PlayerId, dx: Double, dy: Double) {
        // Paper's relative Angle sends the delta to the client (forceSetRotation with relative flags), so it is added to the
        // view the player really has; an absolute setRotation would snap to the server's stale copy. No teleport, no
        // PlayerTeleportEvent. TeleportFlag.Relative only covers velocity in this Paper (X/Y/Z/YAW/PITCH alias VELOCITY_*).
        val player = server.getPlayer(target.uuid) ?: return
        player.setRotation(Angle.relative(dx.toFloat()), Angle.relative(dy.toFloat()))
    }
}
