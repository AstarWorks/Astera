package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.slf4j.LoggerFactory

/**
 * Paper [IDisplayEntityGateway] (WP5): spawns non-persistent `BlockDisplay` /
 * `ItemDisplay` / `TextDisplay` entities. Item displays resolve the item id as
 * a vanilla material key (Astera custom items need the Items plugin and are not
 * resolvable from Combat). Main thread only.
 */
public class PaperDisplayEntityGateway(
    private val server: Server,
) : IDisplayEntityGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>): DisplayHandle? {
        val bukkitWorld = server.getWorld(world.value) ?: return null
        val location = Location(bukkitWorld, at.x, at.y, at.z)
        val display: Display = when (val kind = spec.kind) {
            is DisplayKind.Block -> {
                val data = runCatching { server.createBlockData(blockKey(kind.blockKey)) }.getOrNull()
                    ?: return unsupported("unknown block '${kind.blockKey}'")
                bukkitWorld.spawn(location, BlockDisplay::class.java) { it.block = data }
            }
            is DisplayKind.Item -> {
                val material = Material.matchMaterial(kind.itemId.value)
                    ?: Material.matchMaterial(kind.itemId.value.replace('-', '_'))
                    ?: return unsupported("item '${kind.itemId.value}' is not a vanilla material")
                bukkitWorld.spawn(location, ItemDisplay::class.java) {
                    it.setItemStack(ItemStack(material))
                    it.itemDisplayTransform = itemMode(kind.displayMode)
                }
            }
            is DisplayKind.Text -> bukkitWorld.spawn(location, TextDisplay::class.java) { text ->
                val lines = renderedLines.map { AsteraTextDeserializer.deserialize(it) }
                text.text(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines))
                text.backgroundColor = Color.fromARGB(kind.backgroundArgb)
                text.isShadowed = kind.shadow
                text.isSeeThrough = kind.seeThrough
                kind.lineWidth?.let { text.lineWidth = it }
                text.textOpacity = kind.textOpacity.toByte()
                kind.textRgb?.let { text.text(text.text().color(TextColor.color(it))) }
            }
        }
        com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(display, "display-entity")
        display.billboard = when (spec.billboard) {
            DisplayBillboard.FIXED -> Display.Billboard.FIXED
            DisplayBillboard.VERTICAL -> Display.Billboard.VERTICAL
            DisplayBillboard.HORIZONTAL -> Display.Billboard.HORIZONTAL
            DisplayBillboard.CENTER -> Display.Billboard.CENTER
        }
        display.transformation = transformation(spec.transform)
        spec.brightness?.let { display.brightness = Display.Brightness(it, it) }
        spec.glowArgb?.let {
            display.isGlowing = true
            display.glowColorOverride = Color.fromARGB(it)
        }
        spec.viewRange?.let { display.viewRange = it }
        return DisplayHandle(display.uniqueId)
    }

    override fun update(handle: DisplayHandle, transform: DisplayTransform, interpolationTicks: Int) {
        val display = server.getEntity(handle.id) as? Display ?: return
        display.interpolationDelay = 0
        display.interpolationDuration = interpolationTicks.coerceAtLeast(0)
        display.transformation = transformation(transform)
    }

    override fun setBlock(handle: DisplayHandle, blockKey: String) {
        val display = server.getEntity(handle.id) as? BlockDisplay ?: return
        runCatching { server.createBlockData(blockKey(blockKey)) }.onSuccess { display.block = it }
    }

    override fun setText(handle: DisplayHandle, renderedLines: List<String>) {
        val display = server.getEntity(handle.id) as? TextDisplay ?: return
        val lines = renderedLines.map { AsteraTextDeserializer.deserialize(it) }
        display.text(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines))
    }

    override fun setTextStyle(handle: DisplayHandle, backgroundArgb: Int, textOpacity: Int, textRgb: Int?) {
        val display = server.getEntity(handle.id) as? TextDisplay ?: return
        display.backgroundColor = Color.fromARGB(backgroundArgb)
        display.textOpacity = textOpacity.coerceIn(0, 255).toByte()
        if (textRgb != null) display.text(display.text().color(TextColor.color(textRgb)))
    }

    override fun teleport(handle: DisplayHandle, at: Vec3, yaw: Float, pitch: Float, teleportTicks: Int) {
        val display = server.getEntity(handle.id) as? Display ?: return
        display.teleportDuration = teleportTicks.coerceIn(0, 59)
        display.teleport(Location(display.world, at.x, at.y, at.z, yaw, pitch))
    }

    private fun transformation(t: DisplayTransform): Transformation = Transformation(
        Vector3f(t.translation.x.toFloat(), t.translation.y.toFloat(), t.translation.z.toFloat()),
        Quaternionf(t.rotation.x, t.rotation.y, t.rotation.z, t.rotation.w),
        Vector3f(t.scale.x.toFloat(), t.scale.y.toFloat(), t.scale.z.toFloat()),
        Quaternionf(),
    )

    private fun itemMode(mode: DisplayItemMode): ItemDisplay.ItemDisplayTransform =
        ItemDisplay.ItemDisplayTransform.valueOf(mode.name)

    override fun remove(handle: DisplayHandle) {
        server.getEntity(handle.id)?.remove()
    }

    private fun blockKey(key: String): String = if (':' in key) key else "minecraft:$key"

    private fun unsupported(reason: String): DisplayHandle? {
        log.warn("SpawnDisplay dropped: {}", reason)
        return null
    }
}
