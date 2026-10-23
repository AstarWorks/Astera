package com.astarworks.astera.core.adapter.paper.animation

import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.RigPartTransform
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigMarker
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigPlayback
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType
import org.bukkit.profile.PlayerTextures
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [ISkinRigAnimator] (ADR-0250): while an animation plays, the player's body is
 * replaced by a rig of seven-at-most [ItemDisplay]s.
 *
 *  - **Bones.** One `player_head` ItemDisplay per [SkinRigBone], each carrying the player's own profile
 *    (so the client fetches the real skin) and the pack item model `astera:skinrig`. The head item
 *    renders as an 8 px cube; the pack's core shader (`content/shaders/`) recognises the display by its
 *    brightness override ([SkinRigMarker]) and remaps the skin UVs to that bone's body part, while the
 *    display transform stretches the cube to the bone's box.
 *  - **Follow.** Every tick each display is teleported to the player's feet with the player's *body* yaw
 *    and `teleport_duration = 1`; the pose is re-sampled and written as a new transformation with
 *    `interpolation_duration = 1`, so the client lerps both. (Riding the player as a passenger would track
 *    perfectly smoothly, but the vanilla attachment height changes with pose and passengers do not follow
 *    body yaw, so the explicit teleport was chosen; the cost is up to one tick of lag while moving, which
 *    `lock_movement` keeps small.)
 *  - **Hiding the real player.** `isInvisible = true` hides the body; armor and held items are blanked with
 *    equipment packets to tracking viewers (re-sent every two ticks) and restored at the end. The player's
 *    own first-person view is unchanged and the vanilla hitbox stays.
 *  - **Held item.** The main-hand item is shown by an extra ItemDisplay riding the right-arm transform
 *    (`THIRDPERSON_RIGHTHAND` context).
 *
 * Main-thread only. A rig is torn down on finish, death, quit, world change failure or [shutdown].
 */
public class PaperSkinRigAnimator(
    private val server: Server,
    private val scheduler: IScheduler,
    private val registry: () -> ISkinRigRegistry?,
) : ISkinRigAnimator, Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Rig(
        val player: Player,
        val playback: SkinRigPlayback,
        val slim: Boolean,
        val displays: Map<SkinRigBone, ItemDisplay>,
        val wasInvisible: Boolean,
    ) {
        var held: ItemDisplay? = null
        var heldStack: ItemStack? = null
        var lockedMovement: Boolean = false
    }

    private val rigs = LinkedHashMap<PlayerId, Rig>()
    private var task: IScheduler.TaskHandle? = null

    /** Registers the quit listener; call once from the plugin's `onEnable`. */
    public fun start(plugin: JavaPlugin) {
        server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler
    public fun onQuit(event: PlayerQuitEvent) {
        rigs[PlayerId(event.player.uniqueId)]?.let { teardown(it) }
    }

    /** Removes every rig and restores every player; call from `onDisable`. */
    public fun shutdown() {
        rigs.values.toList().forEach { teardown(it) }
        task?.cancel()
        task = null
    }

    override fun play(playerId: PlayerId, animation: CustomAnimationId): Result<Unit, SkinRigError> {
        if (!server.isPrimaryThread) return Result.failure(SkinRigError.Platform("off-main"))
        val definition = registry()?.find(animation) ?: return Result.failure(SkinRigError.UnknownAnimation(animation))
        val player = server.getPlayer(playerId.uuid) ?: return Result.failure(SkinRigError.UnknownPlayer(playerId))
        if (player.isDead) return Result.failure(SkinRigError.Platform("player-dead"))
        rigs[playerId]?.let { teardown(it) }

        val profile = player.playerProfile
        val slim = profile.textures.skinModel == PlayerTextures.SkinModel.SLIM
        val head = ItemStack(Material.PLAYER_HEAD).apply {
            editMeta(SkullMeta::class.java) { meta ->
                meta.playerProfile = profile
                meta.itemModel = ITEM_MODEL
            }
        }
        val playback = SkinRigPlayback(definition)
        val owner = "skinrig:${playerId.uuid}"
        val origin = anchor(player)
        val rest = SkinRigTransforms.compute(playback.frame(), slim, headLook(player))
        val displays = SkinRigBone.entries.associateWith { bone ->
            origin.world.spawn(origin, ItemDisplay::class.java) { d ->
                PaperManagedEntities.mark(d, owner)
                d.setItemStack(head)
                d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                d.brightness = Display.Brightness(SkinRigMarker.BLOCK_LIGHT, SkinRigMarker.skyPayload(bone, slim))
                d.billboard = Display.Billboard.FIXED
                d.shadowRadius = 0f
                d.interpolationDuration = 1
                d.teleportDuration = 1
                d.transformation = transformation(rest.bones.getValue(bone))
            }
        }
        val rig = Rig(player, playback, slim, displays, player.isInvisible)
        rigs[playerId] = rig
        player.isInvisible = true
        if (definition.lockMovement) lockMovement(player, true).also { rig.lockedMovement = true }
        hideEquipment(rig)
        ensureTask()
        return Result.success(Unit)
    }

    override fun stop(playerId: PlayerId): Result<Unit, SkinRigError> {
        val rig = rigs[playerId] ?: return Result.failure(SkinRigError.NotPlaying(playerId))
        rig.playback.requestStop()
        return Result.success(Unit)
    }

    override fun active(playerId: PlayerId): CustomAnimationId? = rigs[playerId]?.playback?.animation?.id

    // ---- tick -----------------------------------------------------------------

    private fun ensureTask() {
        if (task?.isActive == true) return
        task = scheduler.runRepeating(ticks(1), ticks(1)) { tick() }
    }

    private fun tick() {
        for (rig in rigs.values.toList()) {
            try {
                step(rig)
            } catch (e: Exception) {
                log.warn("Skin rig tick failed for {}; tearing it down", rig.player.name, e)
                teardown(rig)
            }
        }
        if (rigs.isEmpty()) {
            task?.cancel()
            task = null
        }
    }

    private fun step(rig: Rig) {
        val player = rig.player
        if (!player.isOnline || player.isDead || rigs[PlayerId(player.uniqueId)] !== rig) {
            teardown(rig)
            return
        }
        rig.playback.advance()
        if (rig.playback.isFinished) {
            teardown(rig)
            return
        }
        val transforms = SkinRigTransforms.compute(rig.playback.frame(), rig.slim, headLook(player))
        val at = anchor(player)
        for ((bone, display) in rig.displays) {
            display.teleport(at)
            display.transformation = transformation(transforms.bones.getValue(bone))
        }
        updateHeld(rig, at, transforms.heldItem)
        if (!player.isInvisible) player.isInvisible = true
        if (rig.playback.tick % 2 == 0) hideEquipment(rig)
    }

    private fun updateHeld(rig: Rig, at: Location, part: RigPartTransform) {
        val current = rig.player.inventory.itemInMainHand
        if (current.type.isAir) {
            rig.held?.remove()
            rig.held = null
            rig.heldStack = null
            return
        }
        var display = rig.held
        if (display == null || rig.heldStack?.isSimilar(current) != true) {
            display?.remove()
            display = at.world.spawn(at, ItemDisplay::class.java) { d ->
                PaperManagedEntities.mark(d, "skinrig:${rig.player.uniqueId}")
                d.setItemStack(current.clone())
                d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
                d.billboard = Display.Billboard.FIXED
                d.shadowRadius = 0f
                d.interpolationDuration = 1
                d.teleportDuration = 1
                d.transformation = transformation(part)
            }
            rig.held = display
            rig.heldStack = current.clone()
            return
        }
        display.teleport(at)
        display.transformation = transformation(part)
    }

    // ---- teardown -------------------------------------------------------------

    private fun teardown(rig: Rig) {
        val player = rig.player
        val id = PlayerId(player.uniqueId)
        if (rigs[id] === rig) rigs.remove(id)
        rig.displays.values.forEach { it.remove() }
        rig.held?.remove()
        if (rig.lockedMovement) lockMovement(player, false)
        player.isInvisible = rig.wasInvisible || player.hasPotionEffect(PotionEffectType.INVISIBILITY)
        showEquipment(rig)
    }

    // ---- player body hiding ----------------------------------------------------

    private fun hideEquipment(rig: Rig) {
        val viewers = rig.player.trackedBy
        if (viewers.isEmpty()) return
        val blank = ItemStack(Material.AIR)
        val packet = HIDDEN_SLOTS.associateWith { blank }
        viewers.forEach { it.sendEquipmentChange(rig.player, packet) }
    }

    private fun showEquipment(rig: Rig) {
        val player = rig.player
        if (!player.isOnline) return
        val viewers = player.trackedBy
        if (viewers.isEmpty()) return
        val packet = HIDDEN_SLOTS.associateWith { player.inventory.getItem(it) ?: ItemStack(Material.AIR) }
        viewers.forEach { it.sendEquipmentChange(player, packet) }
    }

    private fun lockMovement(player: Player, locked: Boolean) {
        val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.modifiers.filter { it.key == LOCK_KEY }.forEach { attribute.removeModifier(it) }
        if (locked) attribute.addModifier(AttributeModifier(LOCK_KEY, LOCK_AMOUNT, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
    }

    // ---- geometry glue ---------------------------------------------------------

    /** Feet position with the body yaw, so the Display entity's own yaw matches the rig frame. */
    private fun anchor(player: Player): Location =
        player.location.also { it.yaw = player.bodyYaw; it.pitch = 0f }

    /** Live head look as an outer rotation: pitch about X, and the head-to-body yaw delta about Y (MC yaw is clockwise). */
    private fun headLook(player: Player): Vec3 {
        var delta = player.location.yaw - player.bodyYaw
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return Vec3(player.location.pitch.toDouble(), -delta.coerceIn(-75f, 75f).toDouble(), 0.0)
    }

    private fun transformation(part: RigPartTransform): Transformation = Transformation(
        Vector3f(part.translation.x.toFloat(), part.translation.y.toFloat(), part.translation.z.toFloat()),
        Quaternionf(part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w),
        Vector3f(part.scale.x.toFloat(), part.scale.y.toFloat(), part.scale.z.toFloat()),
        Quaternionf(),
    )

    private companion object {
        /** Pack item definition `astera:skinrig` (content/models/skinrig). */
        val ITEM_MODEL = NamespacedKey("astera", "skinrig")
        val LOCK_KEY = NamespacedKey("astera", "skinrig_lock")

        /** `MULTIPLY_SCALAR_1`: speed x (1 - 0.85) while a `lock_movement` animation plays. */
        const val LOCK_AMOUNT = -0.85

        val HIDDEN_SLOTS = listOf(
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
        )
    }
}
