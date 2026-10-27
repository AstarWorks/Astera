package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IObjectiveStage
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * The objective stage on Paper. A carrier gets the Glowing flag (the nameplate scoreboard team colours the outline)
 * and a [BlockDisplay] riding on the head, hidden from the carrier's own view. A marker is a glowing, always-bright
 * [BlockDisplay] with a glow colour override, so it shows through walls. Boss bars are Adventure bars kept per
 * player and key.
 */
public class PaperObjectiveStage(private val plugin: JavaPlugin) : IObjectiveStage {
    private val carried: MutableMap<PlayerId, List<UUID>> = mutableMapOf()
    private val markers: MutableMap<Pair<MatchId, String>, UUID> = mutableMapOf()
    private val labels: MutableMap<Pair<MatchId, String>, UUID> = mutableMapOf()
    private val spinStep: MutableMap<Pair<MatchId, String>, Int> = mutableMapOf()
    private val bars: MutableMap<Pair<PlayerId, String>, BossBar> = mutableMapOf()
    private val mini = MiniMessage.miniMessage()

    override fun setCarrying(player: PlayerId, look: ObjectiveLook?, count: Int) {
        removeCarried(player)
        val online = Bukkit.getPlayer(player.uuid) ?: return
        if (look == null) {
            online.isGlowing = false
            return
        }
        online.isGlowing = true
        // A stack over the head: one block per carried piece (capped), each lifted by its own height.
        val shown = count.coerceIn(1, MAX_STACK)
        val stack = (0 until shown).map { i ->
            val display = online.world.spawn(online.location, BlockDisplay::class.java) { d ->
                d.block = Bukkit.createBlockData(look.block)
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)
                d.transformation = scaled(look.scale, lift = 0.15f + i * look.scale.toFloat() * 0.95f)
                d.teleportDuration = 1
            }
            online.addPassenger(display)
            online.hideEntity(plugin, display)
            display.uniqueId
        }
        carried[player] = stack
    }

    private fun removeCarried(player: PlayerId) {
        carried.remove(player)?.forEach { Bukkit.getEntity(it)?.remove() }
    }

    override fun showMarker(match: MatchId, key: String, world: WorldId, at: Vec3, look: ObjectiveLook) {
        val w = Bukkit.getWorld(world.value) ?: return
        val id = markers[match to key]
        val existing = id?.let { Bukkit.getEntity(it) } as? BlockDisplay
        val where = Location(w, at.x, at.y, at.z)
        val display = if (existing != null && existing.isValid) {
            existing.teleportAsync(where)
            existing
        } else {
            spinStep.remove(match to key)
            w.spawn(where, BlockDisplay::class.java) { d ->
                d.block = Bukkit.createBlockData(look.block)
                d.isPersistent = false
                d.isGlowing = true
                d.glowColorOverride = glowColor(look.glow)
                d.brightness = Display.Brightness(15, 15)
                d.transformation = scaled(look.scale, lift = 0f, centred = true)
                d.viewRange = VIEW_RANGE
            }.also { markers[match to key] = it.uniqueId }
        }
        if (look.spin) spin(match, key, display, look)
        showLabel(match, key, w, where, look)
    }

    /** One interpolated step of the slow turn + bob; the caller refreshes about once a second, the client tweens the rest. */
    private fun spin(match: MatchId, key: String, display: BlockDisplay, look: ObjectiveLook) {
        val step = (spinStep[match to key] ?: 0) + 1
        spinStep[match to key] = step
        val s = look.scale.toFloat()
        val bob = if (step % 2 == 0) 0f else BOB_HEIGHT
        val turn = Quaternionf().rotateY(Math.toRadians(SPIN_DEGREES * step.toDouble()).toFloat())
        // Rotating about the block centre: shift the origin by half the edge, rotate, then shift back.
        val centre = Vector3f(s / 2f, s / 2f, s / 2f)
        val offset = Vector3f(-s / 2f, bob, -s / 2f).add(centre).sub(Vector3f(centre).rotate(turn))
        display.interpolationDelay = 0
        display.interpolationDuration = SPIN_TWEEN_TICKS
        display.transformation = Transformation(offset, turn, Vector3f(s, s, s), Quaternionf())
    }

    private fun showLabel(match: MatchId, key: String, w: org.bukkit.World, where: Location, look: ObjectiveLook) {
        val text = look.label
        if (text == null) {
            labels.remove(match to key)?.let { Bukkit.getEntity(it)?.remove() }
            return
        }
        val above = where.clone().add(0.0, look.scale + LABEL_LIFT, 0.0)
        val existing = labels[match to key]?.let { Bukkit.getEntity(it) } as? TextDisplay
        if (existing != null && existing.isValid) {
            existing.text(mini.deserialize(text))
            existing.teleportAsync(above)
            return
        }
        val label = w.spawn(above, TextDisplay::class.java) { d ->
            d.text(mini.deserialize(text))
            d.isPersistent = false
            d.billboard = Display.Billboard.CENTER
            d.brightness = Display.Brightness(15, 15)
            d.viewRange = VIEW_RANGE
            d.isSeeThrough = true
        }
        labels[match to key] = label.uniqueId
    }

    override fun removeMarker(match: MatchId, key: String) {
        markers.remove(match to key)?.let { Bukkit.getEntity(it)?.remove() }
        labels.remove(match to key)?.let { Bukkit.getEntity(it)?.remove() }
        spinStep.remove(match to key)
    }

    override fun showBar(player: PlayerId, key: String, title: String, progress: Double, color: ObjectiveBarColor) {
        val online = Bukkit.getPlayer(player.uuid) ?: return
        val component = mini.deserialize(title)
        val bar = bars[player to key] ?: BossBar.bossBar(component, 0f, barColor(color), BossBar.Overlay.PROGRESS).also {
            bars[player to key] = it
            online.showBossBar(it)
        }
        bar.name(component)
        bar.color(barColor(color))
        bar.progress(progress.coerceIn(0.0, 1.0).toFloat())
    }

    override fun hideBar(player: PlayerId, key: String) {
        val bar = bars.remove(player to key) ?: return
        Bukkit.getPlayer(player.uuid)?.hideBossBar(bar)
    }

    override fun clearMatch(match: MatchId, players: Collection<PlayerId>) {
        (markers.keys + labels.keys).filter { it.first == match }.forEach { removeMarker(it.first, it.second) }
        for (player in players) {
            removeCarried(player)
            Bukkit.getPlayer(player.uuid)?.isGlowing = false
            bars.keys.filter { it.first == player }.forEach { hideBar(it.first, it.second) }
        }
    }

    private fun scaled(scale: Double, lift: Float, centred: Boolean = false): Transformation {
        val s = scale.toFloat()
        val offset = Vector3f(-s / 2f, if (centred) 0f else lift, -s / 2f)
        return Transformation(offset, Quaternionf(), Vector3f(s, s, s), Quaternionf())
    }

    private fun glowColor(name: String?): Color = when (name) {
        "red" -> Color.fromRGB(255, 70, 70)
        "blue" -> Color.fromRGB(80, 120, 255)
        "green" -> Color.fromRGB(80, 230, 110)
        "yellow", "gold" -> Color.fromRGB(255, 205, 60)
        "aqua" -> Color.fromRGB(80, 230, 255)
        else -> Color.WHITE
    }

    private fun barColor(color: ObjectiveBarColor): BossBar.Color = when (color) {
        ObjectiveBarColor.WHITE -> BossBar.Color.WHITE
        ObjectiveBarColor.RED -> BossBar.Color.RED
        ObjectiveBarColor.BLUE -> BossBar.Color.BLUE
        ObjectiveBarColor.GREEN -> BossBar.Color.GREEN
        ObjectiveBarColor.YELLOW -> BossBar.Color.YELLOW
        ObjectiveBarColor.PURPLE -> BossBar.Color.PURPLE
        ObjectiveBarColor.PINK -> BossBar.Color.PINK
    }

    private companion object {
        const val VIEW_RANGE: Float = 2.0f
        const val MAX_STACK: Int = 5
        const val BOB_HEIGHT: Float = 0.25f
        const val SPIN_DEGREES: Double = 90.0
        const val SPIN_TWEEN_TICKS: Int = 20
        const val LABEL_LIFT: Double = 0.5
    }
}
