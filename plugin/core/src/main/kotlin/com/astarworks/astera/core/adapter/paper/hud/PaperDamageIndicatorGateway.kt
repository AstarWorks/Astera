package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.application.port.outbound.IDamageIndicatorGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.hud.DamageIndicatorStyle
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Server
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.concurrent.ThreadLocalRandom

/**
 * Paper [IDamageIndicatorGateway] (ADR-0206): one short-lived `TextDisplay` per hit,
 * hidden from everyone by default and shown to the attacker only
 * ([org.bukkit.entity.Player.showEntity]). It billboards towards the camera, rises
 * about 0.9 block over 14 ticks (client-side interpolation, no per-tick work on the
 * server) and is removed after [LIFETIME_TICKS]. Not persistent, so a crash can
 * never leave one behind in a saved chunk.
 *
 * Main thread only.
 */
public class PaperDamageIndicatorGateway(
    private val server: Server,
    private val plugin: Plugin,
    private val scheduler: IScheduler,
) : IDamageIndicatorGateway {

    override fun show(viewer: PlayerId, target: PlayerId, amount: Int, style: DamageIndicatorStyle) {
        val viewerPlayer = server.getPlayer(viewer.uuid) ?: return
        val targetPlayer = server.getPlayer(target.uuid) ?: return
        val rng = ThreadLocalRandom.current()
        val at = targetPlayer.eyeLocation.add(rng.nextDouble(-0.45, 0.45), 0.35, rng.nextDouble(-0.45, 0.45))
        val scale = if (style == DamageIndicatorStyle.CRITICAL) 1.5f else 1.0f

        val display = at.world.spawn(at, TextDisplay::class.java) { entity ->
            entity.isVisibleByDefault = false
            entity.isPersistent = false
            entity.billboard = Display.Billboard.CENTER
            entity.text(label(amount, style))
            entity.isShadowed = true
            entity.backgroundColor = org.bukkit.Color.fromARGB(0, 0, 0, 0)
            entity.viewRange = 0.5f
            entity.interpolationDuration = RISE_TICKS
            entity.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())
        }
        viewerPlayer.showEntity(plugin, display)

        // Next tick: the new transformation interpolates from the spawn pose, i.e. the number floats up.
        scheduler.runLaterTicks(1) {
            if (display.isValid) {
                display.interpolationDelay = 0
                display.transformation =
                    Transformation(Vector3f(0f, RISE_BLOCKS, 0f), Quaternionf(), Vector3f(scale), Quaternionf())
            }
        }
        scheduler.runLaterTicks(LIFETIME_TICKS) { if (display.isValid) display.remove() }
    }

    private fun label(amount: Int, style: DamageIndicatorStyle): Component {
        val text = Component.text("-$amount")
        return when (style) {
            DamageIndicatorStyle.NORMAL -> text.color(NamedTextColor.WHITE)
            DamageIndicatorStyle.CRITICAL -> text.color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
            DamageIndicatorStyle.BLOCKED -> Component.text("0").color(NamedTextColor.GRAY)
        }
    }

    private companion object {
        const val RISE_TICKS = 14
        const val LIFETIME_TICKS = 20L
        const val RISE_BLOCKS = 0.9f
    }
}
