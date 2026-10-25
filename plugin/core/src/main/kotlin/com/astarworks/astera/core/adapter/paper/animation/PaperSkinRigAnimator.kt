package com.astarworks.astera.core.adapter.paper.animation

import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.presentation.PresentationDisplay
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask
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
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
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
 *  - **Hiding the real player (ADR-0254).** The emote claims an EMOTE lease of [IPlayerPresentation]: the real body, armor and
 *    held item are hidden by the lease service in one bundle with the reveal of the (staged) rig, and given back by its release.
 *    This class sends no equipment or invisibility packets itself. A skill performance preempts an emote, and an emote is
 *    refused ([SkinRigError.Busy]) while a performance runs. The player's own first-person view and the hitbox are unchanged.
 *  - **Held item.** The main-hand item is shown by an extra ItemDisplay riding the right-arm transform
 *    (`THIRDPERSON_RIGHTHAND` context), only while the lease is held. Skill performances do not use this transport: they ride
 *    the caster as passengers (see the VFX player).
 *
 * Main-thread only. A rig is torn down on finish, death, quit, world change failure or [shutdown].
 */
public class PaperSkinRigAnimator(
    private val server: Server,
    private val scheduler: IScheduler,
    private val registry: () -> ISkinRigRegistry?,
    private val presentation: () -> IPlayerPresentation?,
) : ISkinRigAnimator {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Rig(
        val player: Player,
        val playback: SkinRigPlayback,
        val slim: Boolean,
        val displays: Map<SkinRigBone, ItemDisplay>,
    ) {
        var held: ItemDisplay? = null
        var lease: PresentationLease? = null
        var lockedMovement: Boolean = false

        /** Estimated client-side body yaw (see [PaperSkinRigAnimator.advanceBodyYaw]); seeded with the head yaw. */
        var bodyYaw: Float = player.location.yaw
        var lastX: Double = player.location.x
        var lastZ: Double = player.location.z
    }

    private val rigs = LinkedHashMap<PlayerId, Rig>()
    private var task: IScheduler.TaskHandle? = null

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
        val lease = when (val claimed = presentation()?.claim(
            playerId, EMOTE_MASK, PresentationKind.EMOTE, onRevoked = { rigs[playerId]?.let { teardown(it, revoked = true) } },
        )) {
            null -> null
            is Result.Success -> claimed.value
            is Result.Failure -> return Result.failure(SkinRigError.Busy(playerId))
        }

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
        val origin = player.location.also { it.pitch = 0f }
        val rest = SkinRigTransforms.compute(playback.frame(), slim, Vec3.ZERO)
        val displays = SkinRigBone.entries.associateWith { bone ->
            origin.world.spawn(origin, ItemDisplay::class.java) { d ->
                PaperManagedEntities.mark(d, owner)
                d.setItemStack(head)
                d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                d.brightness = Display.Brightness(SkinRigMarker.BLOCK_LIGHT, SkinRigMarker.skyPayload(bone, slim))
                d.billboard = Display.Billboard.FIXED
                // The hidden player's shadow disappears with them; the body display stands in for it (vanilla 0.5 / 1.0).
                d.shadowRadius = if (bone == SkinRigBone.BODY) SHADOW_RADIUS else 0f
                d.shadowStrength = if (bone == SkinRigBone.BODY) SHADOW_STRENGTH else 0f
                d.interpolationDuration = 1
                d.teleportDuration = 1
                // Staged: invisible until the lease reveals them in the bundle that also hides the real body (ADR-0254).
                d.viewRange = if (lease != null) 0f else 1f
                d.transformation = transformation(rest.bones.getValue(bone), cancelItemFlip = true)
            }
        }
        val rig = Rig(player, playback, slim, displays)
        rig.lease = lease
        rigs[playerId] = rig
        spawnHeld(rig, origin, rest.heldItem, staged = lease != null)
        if (definition.lockMovement) lockMovement(player, true).also { rig.lockedMovement = true }
        if (lease != null) {
            val shown = (displays.values + listOfNotNull(rig.held)).map { PresentationDisplay(it.uniqueId, 1f) }
            presentation()?.reveal(lease, shown)
        }
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
        advanceBodyYaw(rig)
        val transforms = SkinRigTransforms.compute(rig.playback.frame(), rig.slim, headLook(rig))
        val at = anchor(rig)
        for ((bone, display) in rig.displays) {
            display.teleport(at)
            display.transformation = transformation(transforms.bones.getValue(bone), cancelItemFlip = true)
        }
        rig.held?.let { held ->
            held.teleport(at)
            held.transformation = transformation(transforms.heldItem)
        }
    }

    /**
     * The 7th display shows the main-hand stack, only because this emote holds the hand lease (a performance would draw its own
     * single weapon and refuses the emote). The hotbar is frozen while the lease is held, so the stack cannot change.
     */
    private fun spawnHeld(rig: Rig, at: Location, part: RigPartTransform, staged: Boolean) {
        val current = rig.player.inventory.itemInMainHand
        if (current.type.isAir || !staged) return
        rig.held = at.world.spawn(at, ItemDisplay::class.java) { d ->
            PaperManagedEntities.mark(d, "skinrig:${rig.player.uniqueId}")
            d.setItemStack(current.clone())
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
            d.billboard = Display.Billboard.FIXED
            d.shadowRadius = 0f
            d.interpolationDuration = 1
            d.teleportDuration = 1
            d.viewRange = 0f
            d.transformation = transformation(part)
        }
    }

    // ---- teardown -------------------------------------------------------------

    /** [revoked]: the lease was taken away (quit, world change, a performance): nothing to give back, only to remove. */
    private fun teardown(rig: Rig, revoked: Boolean = false) {
        val player = rig.player
        val id = PlayerId(player.uniqueId)
        if (rigs[id] === rig) rigs.remove(id)
        if (rig.lockedMovement) lockMovement(player, false)
        val all = rig.displays.values + listOfNotNull(rig.held)
        val lease = rig.lease
        rig.lease = null
        if (lease != null && !revoked) {
            // Owner leg first (hand back + displays scaled to zero), others in one bundle with the removal; the entities go a tick later.
            presentation()?.release(lease, all.map { it.uniqueId })
            scheduler.runLater(ticks(1)) { all.forEach { it.remove() } }
        } else {
            all.forEach { it.remove() }
        }
    }

    private fun lockMovement(player: Player, locked: Boolean) {
        val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.modifiers.filter { it.key == LOCK_KEY }.forEach { attribute.removeModifier(it) }
        if (locked) attribute.addModifier(AttributeModifier(LOCK_KEY, LOCK_AMOUNT, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
    }

    // ---- geometry glue ---------------------------------------------------------

    /** Feet position with the rig body yaw, so the Display entity's own yaw matches the rig frame. */
    private fun anchor(rig: Rig): Location =
        rig.player.location.also { it.yaw = rig.bodyYaw; it.pitch = 0f }

    /**
     * The server's `player.bodyYaw` is not what viewers draw: a client starts a remote player's body at its head
     * yaw when it begins tracking and only runs the vanilla clamp from there, so the two histories diverge (up to
     * the 50 degree clamp). The rig therefore keeps its own estimate, seeded with the head yaw at `play` and
     * advanced like vanilla `LivingEntity.tickHeadTurn`: while moving the body eases toward the head yaw, and the
     * head may never be more than 50 degrees off the body. On blend-out the real body (which the client kept
     * updating while hidden) is where this estimate ends up.
     */
    private fun advanceBodyYaw(rig: Rig) {
        val loc = rig.player.location
        val dx = loc.x - rig.lastX
        val dz = loc.z - rig.lastZ
        rig.lastX = loc.x
        rig.lastZ = loc.z
        if (dx * dx + dz * dz > MOVED_SQ) rig.bodyYaw += wrapDegrees(loc.yaw - rig.bodyYaw) * 0.3f
        val diff = wrapDegrees(loc.yaw - rig.bodyYaw)
        if (kotlin.math.abs(diff) > MAX_HEAD_OFFSET) rig.bodyYaw += diff - Math.signum(diff) * MAX_HEAD_OFFSET
    }

    private fun wrapDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** Live head look as an outer rotation: pitch about X, and the head-to-body yaw delta about Y (MC yaw is clockwise). */
    private fun headLook(rig: Rig): Vec3 {
        val player = rig.player
        var delta = player.location.yaw - rig.bodyYaw
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return Vec3(player.location.pitch.toDouble(), -delta.coerceIn(-75f, 75f).toDouble(), 0.0)
    }

    /**
     * @param cancelItemFlip bones only: `ItemDisplay` draws its item after a `rotY(180)`, which would turn the skull
     * cube's face to the back; a `right_rotation` of the same angle (applied before the scale) cancels it.
     */
    private fun transformation(part: RigPartTransform, cancelItemFlip: Boolean = false): Transformation = Transformation(
        Vector3f(part.translation.x.toFloat(), part.translation.y.toFloat(), part.translation.z.toFloat()),
        Quaternionf(part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w),
        Vector3f(part.scale.x.toFloat(), part.scale.y.toFloat(), part.scale.z.toFloat()),
        if (cancelItemFlip) Quaternionf().rotationY(Math.PI.toFloat()) else Quaternionf(),
    )

    private companion object {
        /** Pack item definition `astera:skinrig` (content/models/skinrig). */
        val ITEM_MODEL = NamespacedKey("astera", "skinrig")
        val LOCK_KEY = NamespacedKey("astera", "skinrig_lock")

        /** Vanilla player shadow (`AvatarRenderer.shadowRadius` 0.5, full strength), carried by the body display. */
        const val SHADOW_RADIUS = 0.5f
        const val SHADOW_STRENGTH = 1.0f

        /** `MULTIPLY_SCALAR_1`: speed x (1 - 0.85) while a `lock_movement` animation plays. */
        const val LOCK_AMOUNT = -0.85

        /** Vanilla `tickHeadTurn`: a body counts as moved above 0.0025 blocks^2 per tick; head/body offset clamp. */
        const val MOVED_SQ = 0.0025
        const val MAX_HEAD_OFFSET = 50f

        /** An emote replaces the whole body and the held item; it does not pin the pose (the player walks). */
        val EMOTE_MASK = PresentationMask(hand = true, offhand = true, armor = true, body = true)
    }
}
