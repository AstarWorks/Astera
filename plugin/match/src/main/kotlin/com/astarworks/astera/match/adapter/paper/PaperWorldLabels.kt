package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IWorldLabels
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * World labels on Paper: one billboard [TextDisplay] per (label, text), invisible by default and revealed with
 * `Player.showEntity` to the viewers of that text only, so "your base" / "enemy base" share a spot without leaking.
 * Scaled up and see-through with a long view range so a label reads from across the island.
 */
public class PaperWorldLabels(private val plugin: JavaPlugin) : IWorldLabels {
    private class Shown(val entity: UUID, var viewers: Set<PlayerId>)

    private val labels: MutableMap<Pair<MatchId, String>, MutableMap<String, Shown>> = mutableMapOf()
    private val mini = MiniMessage.miniMessage()

    override fun show(match: MatchId, key: String, world: WorldId, at: Vec3, variants: Map<String, Set<PlayerId>>) {
        val w = Bukkit.getWorld(world.value) ?: return
        val where = Location(w, at.x, at.y, at.z)
        val shown = labels.getOrPut(match to key) { mutableMapOf() }
        for (text in shown.keys - variants.keys) shown.remove(text)?.let { Bukkit.getEntity(it.entity)?.remove() }
        for ((text, viewers) in variants) {
            val existing = shown[text]
            var display = existing?.let { Bukkit.getEntity(it.entity) } as? TextDisplay
            if (display == null || !display.isValid || display.world != w) {
                display?.remove()
                display = spawn(w, where, text, match, key)
                shown[text] = Shown(display.uniqueId, emptySet())
            } else if (display.location.distanceSquared(where) > MOVE_EPSILON) {
                display.teleportAsync(where)
            }
            val current = shown.getValue(text)
            for (id in current.viewers - viewers) Bukkit.getPlayer(id.uuid)?.hideEntity(plugin, display)
            for (id in viewers) Bukkit.getPlayer(id.uuid)?.let { if (it.world == w) it.showEntity(plugin, display) }
            current.viewers = viewers
        }
    }

    private fun spawn(w: org.bukkit.World, where: Location, text: String, match: MatchId, key: String): TextDisplay =
        w.spawn(where, TextDisplay::class.java) { d ->
            d.isVisibleByDefault = false
            d.text(mini.deserialize(text))
            d.billboard = Display.Billboard.CENTER
            d.brightness = Display.Brightness(15, 15)
            d.isSeeThrough = true
            d.isShadowed = true
            d.backgroundColor = Color.fromARGB(96, 0, 0, 0)
            d.viewRange = VIEW_RANGE
            d.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(SCALE, SCALE, SCALE), Quaternionf())
            PaperManagedEntities.mark(d, "world-label:${match.value}:$key")
        }

    override fun remove(match: MatchId, key: String) {
        labels.remove(match to key)?.values?.forEach { Bukkit.getEntity(it.entity)?.remove() }
    }

    override fun clearMatch(match: MatchId) {
        labels.keys.filter { it.first == match }.forEach { remove(it.first, it.second) }
    }

    private companion object {
        const val VIEW_RANGE: Float = 2.5f
        const val SCALE: Float = 2.2f
        const val MOVE_EPSILON: Double = 0.01
    }
}
