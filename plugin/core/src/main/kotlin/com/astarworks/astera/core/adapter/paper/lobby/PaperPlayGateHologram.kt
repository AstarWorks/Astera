package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import org.slf4j.LoggerFactory

/**
 * The "▶ Play / プレイ" hologram of the lobby Play gate (ADR-0248): one big text display floating in the
 * arch above the Play NPC, readable from the spawn tile.
 *
 * It is a managed helper entity: non-persistent, tagged, and claimed in [IManagedEntityRegistry] under
 * [OWNER_KEY], so a re-install replaces (never duplicates) it and the janitor can sweep a stray one. Main thread only.
 */
internal class PaperPlayGateHologram(
    private val plugin: JavaPlugin,
    private val messages: IMessageRenderer,
    private val registry: IManagedEntityRegistry?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var display: TextDisplay? = null

    /** [npcFeet] is where the Play NPC stands; the hologram floats just above its head. */
    fun install(worldName: String, npcFeet: Vec3) {
        remove()
        val world = plugin.server.getWorld(worldName) ?: return
        val loc = Location(world, npcFeet.x, npcFeet.y + HEIGHT_ABOVE_FEET, npcFeet.z)
        world.getChunkAt(loc).load()
        registry?.entityOf(OWNER_KEY)?.let { stale -> plugin.server.getEntity(stale)?.remove() }
        val text = MiniMessage.miniMessage().deserialize(messages.render(null, KEY))
        val spawned = world.spawn(loc, TextDisplay::class.java) { d ->
            PaperManagedEntities.mark(d, OWNER_KEY)
            d.billboard = Display.Billboard.CENTER
            d.viewRange = 2.0f
            d.isSeeThrough = false
            d.isShadowed = true
            d.alignment = TextDisplay.TextAlignment.CENTER
            d.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(SCALE, SCALE, SCALE), AxisAngle4f())
            d.text(text)
        }
        registry?.claim(OWNER_KEY, spawned.uniqueId)
        display = spawned
        log.info("Play gate hologram placed at {},{},{} in {}", loc.blockX, loc.blockY, loc.blockZ, worldName)
    }

    fun remove() {
        display?.takeIf { it.isValid }?.remove()
        display = null
        registry?.release(OWNER_KEY)
    }

    private companion object {
        const val OWNER_KEY = "play-gate"
        const val HEIGHT_ABOVE_FEET = 2.85
        const val SCALE = 1.6f
        val KEY = MessageKey("astera.lobby.play.hologram")
    }
}
