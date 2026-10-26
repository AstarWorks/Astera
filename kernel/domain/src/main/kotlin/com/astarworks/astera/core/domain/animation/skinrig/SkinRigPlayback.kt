package com.astarworks.astera.core.domain.animation.skinrig

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * One running play of a [SkinRigAnimation] on one player (ADR-0250). Pure state machine: the
 * adapter calls [frame] once per server tick and [advance] right after, until [isFinished].
 *
 * Timeline (tick `t` = number of [advance] calls so far):
 *  - blend in: weight `min(1, t / blend_in)` toward the sampled pose, from the rest pose;
 *  - body: the animation sampled at `t` (looping animations wrap at `length_ticks`, others hold the last pose);
 *  - blend out: starting at `t = length_ticks` (or at the tick [requestStop] was called) the weight falls
 *    linearly to 0 over `blend_out_ticks`. At weight 0 the pose is exactly the rest pose, so the real player
 *    can be shown again without a visible pop.
 */
public class SkinRigPlayback(public val animation: SkinRigAnimation) {

    public var tick: Int = 0
        private set

    private var stopTick: Int? = null

    /** True once the weight has reached 0 on the way out: the rig can be removed and the real player restored. */
    public val isFinished: Boolean
        get() = outWeight(tick) <= 0.0

    /** Starts blending out now. A no-op if a stop is already pending. */
    public fun requestStop() {
        if (stopTick == null) stopTick = tick
    }

    public fun advance() {
        tick++
    }

    /** The pose to show at the current tick. */
    public fun frame(): RigFrame {
        val weight = inWeight(tick) * outWeight(tick)
        val sampleAt = if (animation.loop) (tick % animation.lengthTicks).toDouble() else minOf(tick, animation.lengthTicks).toDouble()
        val root = blend(animation.root, sampleAt, weight)
        val bones = SkinRigBone.entries.associateWith { bone -> blend(animation.bones[bone], sampleAt, weight) }
        return RigFrame(root, bones)
    }

    private fun inWeight(t: Int): Double {
        val n = animation.blendInTicks
        return if (n == 0) 1.0 else minOf(1.0, t.toDouble() / n)
    }

    private fun outWeight(t: Int): Double {
        val outStart = stopTick ?: if (animation.loop) return 1.0 else animation.lengthTicks
        val n = animation.blendOutTicks
        if (t < outStart) return 1.0
        if (n == 0) return 0.0
        return maxOf(0.0, 1.0 - (t - outStart).toDouble() / n)
    }

    private fun blend(track: RigBoneTrack?, at: Double, weight: Double): BonePose {
        if (track == null || weight <= 0.0) return BonePose.REST
        val rotation = track.rotation?.sample(at)?.let { lerp(Vec3.ZERO, it, weight) } ?: Vec3.ZERO
        val position = track.position?.sample(at)?.let { lerp(Vec3.ZERO, it, weight) } ?: Vec3.ZERO
        val scale = track.scale?.sample(at)?.let { lerp(ONE, it, weight) } ?: ONE
        return BonePose(rotation, position, scale)
    }

    private companion object {
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}
