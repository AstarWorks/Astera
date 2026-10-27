package com.astarworks.astera.core.adapter.paper.hologram

import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IHologramGuide
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Paper concrete of [IHologramGuide] (ADR-0295): one [TextDisplay] per viewer, spawned invisible-by-default and shown
 * to that viewer alone (`Player.showEntity`). A repeating task keeps it two blocks in front of the viewer's eyes and
 * redraws the arrow toward the target. The entity is removed when the viewer leaves, dies, changes world or stops
 * refreshing the guide, and on [shutdown].
 */
public class PaperHologramGuide(
    private val plugin: JavaPlugin,
    private val server: Server,
    private val scheduler: IScheduler,
) : IHologramGuide {

    private class Guide(var target: Vec3, var label: String, var expiresAt: Long) {
        var display: UUID? = null
        var shownText: String? = null
    }

    private val guides: MutableMap<PlayerId, Guide> = ConcurrentHashMap()
    private val mini = MiniMessage.miniMessage()
    private var task: IScheduler.TaskHandle? = null

    public fun start() {
        task = scheduler.runRepeatingTicks(ExecutionScope.Global, UPDATE_TICKS, UPDATE_TICKS) { update() }
    }

    public fun shutdown() {
        task?.cancel()
        task = null
        guides.keys.toList().forEach { hide(it) }
    }

    override fun point(viewer: PlayerId, target: Vec3, label: String, ttlTicks: Int) {
        val expires = scheduler.currentTick() + ttlTicks
        val guide = guides.getOrPut(viewer) { Guide(target, label, expires) }
        guide.target = target
        guide.label = label
        guide.expiresAt = expires
    }

    override fun hide(viewer: PlayerId) {
        val guide = guides.remove(viewer) ?: return
        removeDisplay(guide)
    }

    private fun removeDisplay(guide: Guide) {
        guide.display?.let { server.getEntity(it)?.remove() }
        guide.display = null
        guide.shownText = null
    }

    private fun update() {
        val now = scheduler.currentTick()
        for ((viewer, guide) in guides.entries.toList()) {
            val player = server.getPlayer(viewer.uuid)
            if (player == null || !player.isOnline || now > guide.expiresAt) {
                hide(viewer)
                continue
            }
            if (player.isDead) {
                removeDisplay(guide)
                continue
            }
            follow(player, guide)
        }
    }

    private fun follow(player: Player, guide: Guide) {
        val eye = player.eyeLocation
        var forward = Vector3f(eye.direction.x.toFloat(), 0f, eye.direction.z.toFloat())
        if (forward.lengthSquared() < 1e-6f) forward = Vector3f(0f, 0f, 1f) else forward.normalize()
        val at = Location(eye.world, eye.x + forward.x * AHEAD, eye.y + LIFT, eye.z + forward.z * AHEAD)

        val dx = guide.target.x - eye.x
        val dz = guide.target.z - eye.z
        val arrow = IHologramGuide.arrowFor(forward.x.toDouble(), forward.z.toDouble(), dx, dz)
        val text = "<bold><gold>$arrow</gold></bold>\n<white>${guide.label}</white> <yellow>${hypot(dx, dz).roundToInt()}m</yellow>"

        val existing = guide.display?.let { server.getEntity(it) } as? TextDisplay
        val display = if (existing != null && existing.isValid && existing.world == eye.world) {
            existing.teleportAsync(at)
            existing
        } else {
            removeDisplay(guide)
            eye.world.spawn(at, TextDisplay::class.java) { d ->
                // Invisible by default: nobody but the viewer ever receives the entity.
                d.isVisibleByDefault = false
                d.billboard = Display.Billboard.CENTER
                d.brightness = Display.Brightness(15, 15)
                d.isSeeThrough = true
                d.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                d.teleportDuration = FOLLOW_TWEEN_TICKS
                d.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(SCALE, SCALE, SCALE), Quaternionf())
                PaperManagedEntities.mark(d, "hologram-guide:${player.uniqueId}")
            }.also {
                guide.display = it.uniqueId
                player.showEntity(plugin, it)
            }
        }
        if (guide.shownText != text) {
            display.text(mini.deserialize(text))
            guide.shownText = text
        }
    }

    private companion object {
        const val UPDATE_TICKS: Long = 2L
        const val FOLLOW_TWEEN_TICKS: Int = 2
        const val AHEAD: Double = 2.0
        const val LIFT: Double = -0.15
        const val SCALE: Float = 0.9f
    }
}
