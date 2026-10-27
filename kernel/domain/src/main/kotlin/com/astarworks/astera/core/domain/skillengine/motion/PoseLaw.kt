package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/**
 * Few keys in the parent frame, eased between. `rotate` is an absolute orientation of the local frame
 * (axis-angle from identity), `offset` a local position, `align` an orientation toward a named axis.
 * A key that sets nothing keeps the previous value. Finishes at the last key's tick.
 */
internal class PoseLaw(
    override val frame: FrameId,
    private val spec: MotionSpec.Pose,
    private val resolver: ValueResolver,
) : MotionLaw {
    override val phase: MotionPhase = MotionPhase.TRANSLATE

    private class Sample(val t: Int, val pos: Vec3?, val rot: Quaternion?, val ease: String?)

    private val keys: List<PoseKey> = spec.keys.sortedBy { it.t }
    private var startPos: Vec3? = null
    private var startRot: Quaternion? = null

    private fun sample(ctx: MotionContext, key: PoseKey): Sample {
        val pos = key.offset?.let { resolver.vec3(it) }
        val rot = key.rotate?.let { r ->
            MotionAxes.resolve(r.axis, ctx)?.let { axis ->
                Quaternion.axisAngle(MotionMath.worldDirToLocal(ctx, axis), Math.toRadians(resolver.num(r.deg)))
            }
        } ?: key.align?.let { MotionMath.alignedRotation(ctx, it, null) }
        return Sample(key.t, pos, rot, key.ease)
    }

    override fun step(ctx: MotionContext): MotionStep {
        if (keys.isEmpty()) return MotionStep.Finish()
        val t = ctx.ticksSinceStart
        val fromPos = startPos ?: ctx.local.origin.also { startPos = it }
        val fromRot = startRot ?: ctx.local.rotation.also { startRot = it }
        val samples = keys.map { sample(ctx, it) }
        val last = samples.last()
        val prevIdx = samples.indexOfLast { it.t <= t }
        val nextIdx = samples.indexOfFirst { it.t > t }
        val done = t >= last.t || nextIdx < 0
        val posKeys = samples.filter { it.pos != null }
        val rotKeys = samples.filter { it.rot != null }
        val out = Frame(channelVec(posKeys, t, fromPos), channelRot(rotKeys, t, fromRot).normalized())
        return if (done && prevIdx >= 0) MotionStep.Finish(out) else MotionStep.Move(out)
    }

    private fun channelVec(ks: List<Sample>, t: Int, fallback: Vec3): Vec3 {
        if (ks.isEmpty()) return fallback
        val b = ks.firstOrNull { it.t > t } ?: return ks.last().pos!!
        val a = ks.lastOrNull { it.t <= t } ?: return fallback.let { f ->
            // before the first key: ease from the start pose
            val u = MotionMath.ease(b.ease, t.toDouble() / maxOf(1, b.t))
            MotionMath.lerp(f, b.pos!!, u)
        }
        val u = MotionMath.ease(b.ease, (t - a.t).toDouble() / maxOf(1, b.t - a.t))
        return MotionMath.lerp(a.pos!!, b.pos!!, u)
    }

    private fun channelRot(ks: List<Sample>, t: Int, fallback: Quaternion): Quaternion {
        if (ks.isEmpty()) return fallback
        val b = ks.firstOrNull { it.t > t } ?: return ks.last().rot!!
        val a = ks.lastOrNull { it.t <= t }
        val from = a?.rot ?: fallback
        val span = if (a == null) maxOf(1, b.t) else maxOf(1, b.t - a.t)
        val u = MotionMath.ease(b.ease, (t - (a?.t ?: 0)).toDouble() / span)
        return Quaternion.slerp(from, b.rot!!, u)
    }
}
