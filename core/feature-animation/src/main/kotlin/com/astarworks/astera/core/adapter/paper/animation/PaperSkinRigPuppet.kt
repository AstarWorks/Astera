package com.astarworks.astera.core.adapter.paper.animation

import java.util.concurrent.ConcurrentHashMap

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigPuppet
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.presentation.PresentationDisplay
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.application.skillengine.runtime.VfxTrace
import com.astarworks.astera.core.domain.animation.skinrig.RigAnchor
import com.astarworks.astera.core.domain.animation.skinrig.RigDisplayContract
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.rigFrame
import com.astarworks.astera.core.domain.animation.skinrig.RigPartTransform
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigMarker
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
 * Paper concrete of [ISkinRigPuppet] (ADR-0250, ADR-0315): while shown, the player's body is replaced by a rig of
 * seven-at-most [ItemDisplay]s, posed from outside one frame at a time.
 *
 *  - **Bones.** One `player_head` ItemDisplay per [SkinRigBone], each carrying the player's own profile
 *    (so the client fetches the real skin) and the pack item model `astera:skinrig`. The head item
 *    renders as an 8 px cube; the pack's core shader (`content/shaders/`) recognises the display by its
 *    brightness override ([SkinRigMarker]) and remaps the skin UVs to that bone's body part, while the
 *    display transform stretches the cube to the bone's box.
 *  - **Driven, not clocked (ADR-0315).** The puppet samples nothing itself: [pose] receives the frame and the anchor
 *    (feet, yaw, pitch) its caller computed, and in that same call moves every display there and writes the new
 *    transformation. The move is `teleportAsync` (the only teleport the architecture allows in a Paper adapter, Folia-ready);
 *    within one loaded world it completes before it returns, so the entity is at the anchor in the same tick as the
 *    transformation. A move that did not complete in the call is counted and reported once when the rig goes down, so a lag
 *    has a number instead of an offset. The skill engine pushes the frame of its own frame table, so the body and the weapon
 *    frame riding the hand are drawn from one pose and one anchor; an emote pushes the clip it plays on its own clock.
 *    The earlier free-running tick (own clock, own body-yaw estimate, anchor read a tick late) is gone.
 *  - **One Display contract with the weapon ([RigDisplayContract]).** Every bone stands on the anchor (entity position = feet,
 *    entity yaw = body yaw, the bone's transform in that rig frame) and writes with the contract's `teleport_duration` /
 *    `interpolation_duration`; a rig mesh the skill engine writes is rooted on the same anchor with the same durations. The
 *    client applies an entity position at receipt and a transformation at its next tick, so two displays that move one by
 *    position and one by transformation split by a tick of travel: rooting both the same way is what keeps the weapon in the
 *    fist (the earlier `1 / 1` bones with a position-moved weapon were 0.6 blocks apart on the swing ticks).
 *  - **Hiding the real player (ADR-0276).** [show] claims an EMOTE lease of [IPlayerPresentation]: the real body, armor and
 *    held item are hidden by the lease service in one bundle with the reveal of the (staged) rig, and given back by its release.
 *    This class sends no equipment or invisibility packets itself. A skill performance preempts an emote, and an emote is
 *    refused ([SkinRigError.Busy]) while a performance runs. The player's own first-person view and the hitbox are unchanged.
 *  - **Held item.** The main-hand item is shown by an extra ItemDisplay riding the right-arm transform
 *    (`THIRDPERSON_RIGHTHAND` context), only while the lease is held and the caller asked for it.
 *
 * Main-thread only. A rig is torn down on [hide], death, quit, lease revocation or [shutdown].
 */
public class PaperSkinRigPuppet(
    private val server: Server,
    private val scheduler: IScheduler,
    private val presentation: () -> IPlayerPresentation?,
) : ISkinRigPuppet {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Rig(
        val player: Player,
        val animation: SkinRigAnimation,
        val slim: Boolean,
        val displays: Map<SkinRigBone, ItemDisplay>,
    ) {
        var held: ItemDisplay? = null
        var lease: PresentationLease? = null
        var lockedMovement: Boolean = false
        /** Moves that were still pending when [pose] returned (ADR-0315: the anchor lag, counted not tuned). */
        var deferredMoves: Int = 0
    }

    private val rigs = ConcurrentHashMap<PlayerId, Rig>()

    /** Removes every rig and restores every player; call from `onDisable`. */
    public fun shutdown() {
        rigs.values.toList().forEach { teardown(it) }
    }

    override fun show(playerId: PlayerId, animation: SkinRigAnimation, heldItem: Boolean): Result<Unit, SkinRigError> {
        if (server.getPlayer(playerId.uuid)?.let { org.bukkit.Bukkit.isOwnedByCurrentRegion(it) } != true) return Result.failure(SkinRigError.Platform("off-main"))
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
        val owner = "skinrig:${playerId.uuid}"
        val origin = player.location.also { it.pitch = 0f }
        val rest = SkinRigTransforms.compute(RigFrame.REST, slim, Vec3.ZERO)
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
                d.interpolationDuration = RigDisplayContract.INTERPOLATION_TICKS
                d.teleportDuration = RigDisplayContract.TELEPORT_TICKS
                // Staged: invisible until the lease reveals them in the bundle that also hides the real body (ADR-0276).
                d.viewRange = if (lease != null) 0f else 1f
                d.transformation = transformation(rest.bones.getValue(bone), cancelItemFlip = true)
            }
        }
        val rig = Rig(player, animation, slim, displays)
        rig.lease = lease
        rigs[playerId] = rig
        if (heldItem) spawnHeld(rig, origin, rest.heldItem, staged = lease != null)
        if (animation.lockMovement) lockMovement(player, true).also { rig.lockedMovement = true }
        if (lease != null) {
            val shown = (displays.values + listOfNotNull(rig.held)).map { PresentationDisplay(it.uniqueId, 1f) }
            presentation()?.reveal(lease, shown)
        }
        return Result.success(Unit)
    }

    override fun pose(playerId: PlayerId, frame: RigFrame, anchor: RigAnchor?) {
        val rig = rigs[playerId] ?: return
        val player = rig.player
        if (!player.isOnline || player.isDead) {
            teardown(rig)
            return
        }
        try {
            val at: Location
            val headLook: Vec3
            if (anchor == null) {
                val loc = player.location
                at = loc.clone().also { it.pitch = 0f }
                headLook = Vec3(loc.pitch.toDouble(), 0.0, 0.0)
            } else {
                at = Location(player.world, anchor.feet.x, anchor.feet.y, anchor.feet.z, anchor.yawDeg.toFloat(), 0f)
                headLook = Vec3(anchor.pitchDeg, 0.0, 0.0)
            }
            val transforms = SkinRigTransforms.compute(frame, rig.slim, headLook)
            for ((bone, display) in rig.displays) {
                move(rig, display, at)
                display.transformation = transformation(transforms.bones.getValue(bone), cancelItemFlip = true)
            }
            rig.held?.let { held ->
                move(rig, held, at)
                held.transformation = transformation(transforms.heldItem)
            }
            if (anchor != null && VfxTrace.enabled) traceHand(player, rig, frame, anchor, transforms)
        } catch (e: Exception) {
            log.warn("Skin rig pose failed for {}; tearing it down", player.name, e)
            teardown(rig)
        }
    }

    /** One display to the anchor; a move that is still pending when the call returns is the lag ADR-0315 removed, so it is counted. */
    private fun move(rig: Rig, display: ItemDisplay, at: Location) {
        if (!display.teleportAsync(at).isDone) rig.deferredMoves++
    }

    /**
     * `[vfx-trace] rig hand`: the entity data this pose sends for the right hand (position, yaw, the contract durations) and where
     * the grip (the held-item translation of the rig frame) lands in the world, keyed by the world's `gameTime`; paired with the
     * skill engine's `rig mesh` row of the same tick by `tools/vfx-shot/rig_trace_check.py`.
     */
    private fun traceHand(player: Player, rig: Rig, frame: RigFrame, anchor: RigAnchor, transforms: com.astarworks.astera.core.domain.animation.skinrig.RigTransforms) {
        val local = transforms.heldItem.translation
        val grip = anchor.rigFrame().toWorld(local)
        // target = the pin the engine sent (rig frame), reach = how far that pin is beyond / short of the rigid arm (blocks)
        val target = frame.rightHandTarget
        val reach = SkinRigTransforms.rightHandReach(frame, rig.slim)
        log.info(
            "[vfx-trace] rig hand tick={} player={} at={} yaw={} tp={} ip={} local={} grip={} target={} reach={} slim={}",
            player.world.gameTime, player.uniqueId, fmt(anchor.feet), "%.4f".format(anchor.yawDeg.toFloat()),
            RigDisplayContract.TELEPORT_TICKS, RigDisplayContract.INTERPOLATION_TICKS, fmt(local), fmt(grip),
            target?.let { fmt(it) } ?: "-", reach?.let { "%.4f".format(it) } ?: "-", rig.slim,
        )
        // a `hand: off` rig pins the left hand: its own row (hand=l), the left grip in the world against the off-hand mesh origin
        val leftTarget = frame.leftHandTarget ?: return
        val leftLocal = SkinRigTransforms.leftGrip(frame, rig.slim)
        log.info(
            "[vfx-trace] rig hand tick={} hand=l player={} at={} yaw={} tp={} ip={} local={} grip={} target={} reach={} slim={}",
            player.world.gameTime, player.uniqueId, fmt(anchor.feet), "%.4f".format(anchor.yawDeg.toFloat()),
            RigDisplayContract.TELEPORT_TICKS, RigDisplayContract.INTERPOLATION_TICKS, fmt(leftLocal), fmt(anchor.rigFrame().toWorld(leftLocal)),
            fmt(leftTarget), SkinRigTransforms.leftHandReach(frame, rig.slim)?.let { "%.4f".format(it) } ?: "-", rig.slim,
        )
    }

    private fun fmt(v: Vec3): String = "%.4f,%.4f,%.4f".format(v.x, v.y, v.z)

    override fun hide(playerId: PlayerId) {
        rigs[playerId]?.let { teardown(it) }
    }

    override fun shown(playerId: PlayerId): CustomAnimationId? = rigs[playerId]?.animation?.id

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
            d.interpolationDuration = RigDisplayContract.INTERPOLATION_TICKS
            d.teleportDuration = RigDisplayContract.TELEPORT_TICKS
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
        if (rig.deferredMoves > 0) log.warn("Skin rig of {}: {} display move(s) completed after the tick that posed them (ADR-0315 anchor lag)", player.name, rig.deferredMoves)
        if (rig.lockedMovement) lockMovement(player, false)
        val all = rig.displays.values + listOfNotNull(rig.held)
        val lease = rig.lease
        rig.lease = null
        if (lease != null && !revoked) {
            // Owner leg first (hand back + displays scaled to zero), others in one bundle with the removal; the entities go a tick later.
            presentation()?.release(lease, all.map { it.uniqueId })
            all.forEach { d -> scheduler.run(ExecutionScope.Entity(d.uniqueId), ticks(1)) { d.remove() } }
        } else {
            all.forEach { it.remove() }
        }
    }

    private fun lockMovement(player: Player, locked: Boolean) {
        val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.modifiers.filter { it.key == LOCK_KEY }.forEach { attribute.removeModifier(it) }
        if (locked) attribute.addModifier(AttributeModifier(LOCK_KEY, LOCK_AMOUNT, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
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

        /** An emote replaces the whole body and the held item; it does not pin the pose (the player walks). */
        val EMOTE_MASK = PresentationMask(hand = true, offhand = true, armor = true, body = true)
    }
}
