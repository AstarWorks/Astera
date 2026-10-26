package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.skinrig.BonePose
import com.astarworks.astera.core.domain.animation.skinrig.RigBoneTrack
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.RigQuat
import com.astarworks.astera.core.domain.animation.skinrig.RigTransforms
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigEase
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/** A body animation resolved for one performance: the skin-rig animation and whether the caster's skin is slim. */
public class VfxBodyContext(public val animation: SkinRigAnimation, public val slim: Boolean = false)

/** Pose of one anchor-local landmark (blocks, `PLAYER_SCALE` applied, request scale NOT applied). */
public class Landmark(public val position: Vec3, public val rotation: Quaternion)

/**
 * The performance body sampled once per rig tick (ADR-0276). The animation is sampled with no blend (a performance starts
 * and ends with an atomic swap, so `blend_in` / `blend_out` are ignored). Pure and deterministic: the same input gives
 * the same transforms, which the plan dump and the reviewer's frame-to-key mapping rely on.
 */
public class VfxBodyTrack(public val body: VfxBodyContext) {

    public class State(
        public val frame: RigFrame,
        public val transforms: RigTransforms,
        public val arm: Landmark,
        public val hips: Landmark,
    )

    private val animation = body.animation
    private val cache = HashMap<Int, State>()
    private val snapTicks: Set<Int> = buildSet {
        fun scan(track: RigBoneTrack?) {
            listOfNotNull(track?.rotation, track?.position, track?.scale).forEach { ch ->
                ch.keys.filter { it.ease == SkinRigEase.STEP && it.tick > 0 }.forEach { add(it.tick) }
            }
        }
        scan(animation.root)
        animation.bones.values.forEach(::scan)
    }

    public val lengthTicks: Int get() = animation.lengthTicks

    /** `true` when the pose at [tick] is reached by a cut (a `step` key), not by interpolation. */
    public fun isSnap(tick: Int): Boolean = tick in snapTicks

    public fun at(tick: Int): State = cache.getOrPut(tick.coerceIn(0, animation.lengthTicks)) { compute(tick.coerceIn(0, animation.lengthTicks)) }

    /** Right-arm frame the client shows at fractional rig tick [s]: lerp / slerp between ticks, held across a cut. */
    public fun armAt(s: Double): Landmark {
        val l = Math.floor(s).toInt()
        val u = s - l
        val a = at(l).arm
        if (u <= 1e-9) return a
        val bTick = l + 1
        if (isSnap(bTick)) return a
        val b = at(bTick).arm
        return Landmark(a.position + (b.position - a.position) * u, Quaternion.slerp(a.rotation, b.rotation, u))
    }

    private fun compute(tick: Int): State {
        val frame = sample(tick.toDouble())
        val transforms = SkinRigTransforms.compute(frame, body.slim)
        return State(frame, transforms, landmark(SkinRigTransforms.rightArm(frame, body.slim)), landmark(SkinRigTransforms.hips(frame)))
    }

    private fun landmark(f: SkinRigTransforms.ArmFrame): Landmark = Landmark(f.joint, quat(f.rotation))

    private fun sample(at: Double): RigFrame = RigFrame(
        root = pose(animation.root, at),
        bones = SkinRigBone.entries.associateWith { pose(animation.bones[it], at) },
        leftHandGrip = if (animation.twoHandGrip) 1.0 else 0.0,
    )

    private fun pose(track: RigBoneTrack?, at: Double): BonePose {
        if (track == null) return BonePose.REST
        return BonePose(
            rotation = track.rotation?.sample(at) ?: Vec3.ZERO,
            position = track.position?.sample(at) ?: Vec3.ZERO,
            scale = track.scale?.sample(at) ?: Vec3(1.0, 1.0, 1.0),
        )
    }

    public companion object {
        public fun quat(q: RigQuat): Quaternion = Quaternion(q.x, q.y, q.z, q.w)
    }
}
