package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.adapter.paper.presentation.ConcealedDisplays
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigMarker
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.WeaponRigShading
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.profile.PlayerTextures
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.slf4j.LoggerFactory

/**
 * Paper [IDisplayEntityGateway] (WP5): spawns non-persistent `BlockDisplay` /
 * `ItemDisplay` / `TextDisplay` entities. Item displays resolve the item id as
 * a vanilla material key and optionally apply a resource-pack item_model.
 * No custom-item gameplay data or Items plugin dependency is required. Main thread only.
 */
public class PaperDisplayEntityGateway(
    private val server: Server,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
) : IDisplayEntityGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    /**
     * [height] is the passenger origin height captured at attach time. The pose is pinned for a performance (ADR-0276), so it is a
     * constant for the display's life; reading `player.height` per update let a one-tick pose change move the client attachment
     * while the compensation lerped (hilt and body one tick apart). [flip] marks skin-rig bones (see [transformation]).
     */
    private class Attachment(val anchor: PlayerId, val world: org.bukkit.World,
        var offset: Vec3, var transform: DisplayTransform, val height: Double, val flip: Boolean)
    private val attachments = java.util.concurrent.ConcurrentHashMap<DisplayHandle, Attachment>()

    override fun spawnAttached(world: WorldId, spec: DisplaySpec, renderedLines: List<String>, anchor: PlayerId, offset: Vec3): DisplayHandle? {
        val player = server.getPlayer(anchor.uuid) ?: return null
        if (spec.billboard != DisplayBillboard.FIXED || player.world.name != world.value) return null
        val location = player.location
        val height = player.height
        val origin = Vec3(location.x, location.y + height, location.z)
        // Both the spawn position and its first metadata must already use the riding origin:
        // a post-spawn correction can otherwise render a frame below the player's feet.
        val initial = spec.transform.copy(translation = spec.transform.translation + offset - Vec3(0.0, height, 0.0))
        val handle = spawn(world, origin, spec.copy(transform = initial), renderedLines, player) ?: return null
        val display = server.getEntity(handle.id) as? Display ?: return null
        if (!player.addPassenger(display)) {
            remove(handle)
            return null
        }
        attachments[handle] = Attachment(anchor, player.world, offset, spec.transform, height, spec.kind is DisplayKind.SkinBone)
        return handle
    }

    override fun isSlimSkin(player: PlayerId): Boolean =
        server.getPlayer(player.uuid)?.playerProfile?.textures?.skinModel == PlayerTextures.SkinModel.SLIM

    override fun isAttached(handle: DisplayHandle, anchor: PlayerId, world: WorldId): Boolean {
        val attachment = attachments[handle] ?: return false
        val display = server.getEntity(handle.id) as? Display ?: return false
        return display.isValid && attachment.anchor == anchor && attachment.world.name == world.value &&
            attachmentOwner(handle, display, attachment) != null
    }

    private fun attachmentOwner(handle: DisplayHandle, display: Display, attachment: Attachment): Player? {
        val player = server.getPlayer(attachment.anchor.uuid)
        if (player == null || player.world != attachment.world || display.world != attachment.world || display.vehicle != player) {
            remove(handle)
            return null
        }
        return player
    }

    override fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>): DisplayHandle? =
        spawn(world, at, spec, renderedLines, null)

    private fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>, owner: Player?): DisplayHandle? {
        val bukkitWorld = server.getWorld(world.value) ?: return null
        val location = Location(bukkitWorld, at.x, at.y, at.z)
        val display: Display = when (val kind = spec.kind) {
            is DisplayKind.Block -> {
                val data = runCatching { server.createBlockData(blockKey(kind.blockKey)) }.getOrNull()
                    ?: return unsupported("unknown block '${kind.blockKey}'")
                bukkitWorld.spawn(location, BlockDisplay::class.java) {
                    it.block = data
                    configure(it, spec)
                }
            }
            is DisplayKind.Item -> {
                val material = Material.matchMaterial(kind.itemId.value)
                    ?: Material.matchMaterial(kind.itemId.value.replace('-', '_'))
                    ?: return unsupported("item '${kind.itemId.value}' is not a vanilla material")
                val stack = ItemStack(material)
                kind.itemModel?.let { key ->
                    val model = NamespacedKey.fromString(key) ?: return unsupported("invalid item model '$key'")
                    stack.itemMeta = stack.itemMeta.apply { itemModel = model }
                }
                bukkitWorld.spawn(location, ItemDisplay::class.java) {
                    it.setItemStack(stack)
                    it.itemDisplayTransform = itemMode(kind.displayMode)
                    configure(it, spec)
                }
            }
            is DisplayKind.SkinBone -> {
                val player = owner ?: return unsupported("skin bone '${kind.bone.yamlName}' needs an owner")
                // One player_head per bone carrying the owner's profile (the client fetches the real skin); the pack's core
                // shader reads the bone id from the brightness override (SkinRigMarker) and remaps the skull UVs.
                val head = ItemStack(Material.PLAYER_HEAD).apply {
                    editMeta(SkullMeta::class.java) { meta ->
                        meta.playerProfile = player.playerProfile
                        meta.itemModel = SKINRIG_MODEL
                    }
                }
                bukkitWorld.spawn(location, ItemDisplay::class.java) {
                    it.setItemStack(head)
                    it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    configure(it, spec)
                    it.brightness = Display.Brightness(SkinRigMarker.BLOCK_LIGHT, SkinRigMarker.skyPayload(kind.bone, kind.slim))
                    // The hidden player's shadow disappears with them; the body display stands in for it (vanilla 0.5 / 1.0).
                    it.shadowRadius = if (kind.bone == SkinRigBone.BODY) SHADOW_RADIUS else 0f
                    it.shadowStrength = if (kind.bone == SkinRigBone.BODY) 1.0f else 0f
                }
            }
            DisplayKind.AnchorMainHand -> {
                val player = owner ?: return unsupported("main-hand weapon needs an owner")
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) return unsupported("main hand is empty")
                bukkitWorld.spawn(location, ItemDisplay::class.java) {
                    it.setItemStack(held.clone())
                    it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
                    configure(it, spec)
                    it.shadowRadius = 0f
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
                configure(text, spec)
            }
        }
        return DisplayHandle(display.uniqueId)
    }

    /** Initialize before the entity is added to the world and starts being tracked. */
    private fun configure(display: Display, spec: DisplaySpec) {
        com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(display, "display-entity")
        display.billboard = when (spec.billboard) {
            DisplayBillboard.FIXED -> Display.Billboard.FIXED
            DisplayBillboard.VERTICAL -> Display.Billboard.VERTICAL
            DisplayBillboard.HORIZONTAL -> Display.Billboard.HORIZONTAL
            DisplayBillboard.CENTER -> Display.Billboard.CENTER
        }
        display.transformation = transformation(spec.transform, flip = spec.kind is DisplayKind.SkinBone)
        spec.brightness?.let { display.brightness = Display.Brightness(it, it) }
        if (spec.weaponRigShading) {
            display.brightness = Display.Brightness(WeaponRigShading.BLOCK_LIGHT, WeaponRigShading.SKY_LIGHT)
        }
        spec.glowArgb?.let {
            display.isGlowing = true
            display.glowColorOverride = Color.fromARGB(it)
        }
        spec.viewRange?.let { display.viewRange = it }
    }

    override fun update(handle: DisplayHandle, transform: DisplayTransform, interpolationTicks: Int) {
        if (ConcealedDisplays.contains(handle.id)) return
        if (deferDisplay(handle) { update(handle, transform, interpolationTicks) }) return
        val display = server.getEntity(handle.id) as? Display ?: return
        val attachment = attachments[handle]
        val effective = if (attachment != null) {
            val player = attachmentOwner(handle, display, attachment) ?: return
            attachment.transform = transform
            // Player passenger origin is at the (pinned) standing height captured at attach; content remains feet-relative.
            transform.copy(translation = transform.translation + attachment.offset - Vec3(0.0, attachment.height, 0.0))
        } else transform
        val next = transformation(effective, flip = attachment?.flip == true)
        // Re-sending the start tick without a changed transform restarts client interpolation
        // toward the same endpoint, including when a camera cut starts tracking this entity.
        if (display.transformation == next) return
        display.interpolationDelay = 0
        display.interpolationDuration = interpolationTicks.coerceAtLeast(0)
        display.transformation = next
    }

    override fun setViewRange(handle: DisplayHandle, viewRange: Float) {
        (server.getEntity(handle.id) as? Display)?.viewRange = viewRange
    }

    override fun setBlock(handle: DisplayHandle, blockKey: String) {
        if (deferDisplay(handle) { setBlock(handle, blockKey) }) return
        val display = server.getEntity(handle.id) as? BlockDisplay ?: return
        runCatching { server.createBlockData(blockKey(blockKey)) }.onSuccess { display.block = it }
    }

    override fun setText(handle: DisplayHandle, renderedLines: List<String>) {
        if (deferDisplay(handle) { setText(handle, renderedLines) }) return
        val display = server.getEntity(handle.id) as? TextDisplay ?: return
        val lines = renderedLines.map { AsteraTextDeserializer.deserialize(it) }
        display.text(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines))
    }

    override fun setTextStyle(handle: DisplayHandle, backgroundArgb: Int, textOpacity: Int, textRgb: Int?) {
        if (deferDisplay(handle) { setTextStyle(handle, backgroundArgb, textOpacity, textRgb) }) return
        val display = server.getEntity(handle.id) as? TextDisplay ?: return
        display.backgroundColor = Color.fromARGB(backgroundArgb)
        display.textOpacity = textOpacity.coerceIn(0, 255).toByte()
        if (textRgb != null) display.text(display.text().color(TextColor.color(textRgb)))
    }

    override fun teleport(handle: DisplayHandle, at: Vec3, yaw: Float, pitch: Float, teleportTicks: Int) {
        if (deferDisplay(handle) { teleport(handle, at, yaw, pitch, teleportTicks) }) return
        val display = server.getEntity(handle.id) as? Display ?: return
        val attachment = attachments[handle]
        if (attachment != null) {
            val player = attachmentOwner(handle, display, attachment) ?: return
            val origin = player.location
            attachment.offset = at - Vec3(origin.x, origin.y, origin.z)
            update(handle, attachment.transform, teleportTicks)
            return
        }
        display.teleportDuration = teleportTicks.coerceIn(0, 59)
        display.teleportAsync(Location(display.world, at.x, at.y, at.z, yaw, pitch))
    }

    private fun deferDisplay(handle: DisplayHandle, action: () -> Unit): Boolean {
        val entity = server.getEntity(handle.id) ?: return false
        if (server.isOwnedByCurrentRegion(entity)) return false
        com.astarworks.astera.core.application.service.observability.ExecutionCosts.increment("combat_vfx_owner_hops")
        scheduler.run(com.astarworks.astera.core.application.port.outbound.ExecutionScope.Entity(handle.id), kotlin.time.Duration.ZERO, action)
            .onRetired { attachments.remove(handle) }
        return true
    }

    /** [flip]: skin bones only. `ItemDisplay` draws its item after a `rotY(180)`, which would turn the skull cube's face to the back; a `right_rotation` of the same angle cancels it. */
    private fun transformation(t: DisplayTransform, flip: Boolean = false): Transformation = Transformation(
        Vector3f(t.translation.x.toFloat(), t.translation.y.toFloat(), t.translation.z.toFloat()),
        Quaternionf(t.rotation.x, t.rotation.y, t.rotation.z, t.rotation.w),
        Vector3f(t.scale.x.toFloat(), t.scale.y.toFloat(), t.scale.z.toFloat()),
        if (flip) Quaternionf().rotationY(Math.PI.toFloat()) else Quaternionf(),
    )

    private fun itemMode(mode: DisplayItemMode): ItemDisplay.ItemDisplayTransform =
        ItemDisplay.ItemDisplayTransform.valueOf(mode.name)

    override fun remove(handle: DisplayHandle) {
        if (deferDisplay(handle) { remove(handle) }) return
        attachments.remove(handle)
        ConcealedDisplays.remove(handle.id)
        server.getEntity(handle.id)?.remove()
    }

    private companion object {
        /** Pack item definition `astera:skinrig` (content/models/skinrig). */
        val SKINRIG_MODEL: NamespacedKey = NamespacedKey("astera", "skinrig")
        const val SHADOW_RADIUS = 0.5f
    }

    private fun blockKey(key: String): String = if (':' in key) key else "minecraft:$key"

    private fun unsupported(reason: String): DisplayHandle? {
        log.warn("SpawnDisplay dropped: {}", reason)
        return null
    }
}
