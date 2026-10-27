package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/**
 * Chase a point forever. `lag` is a smoothing time in ticks: the frame closes `1 / (lag + 1)` of the gap each
 * tick (0 or absent = snap). `offset` is added in world space. Orientation is left to the other laws.
 */
internal class FollowLaw(
    override val frame: FrameId,
    private val spec: MotionSpec.Follow,
    private val resolver: ValueResolver,
) : MotionLaw {
    override val phase: MotionPhase = MotionPhase.TRANSLATE

    override fun step(ctx: MotionContext): MotionStep {
        val target = ctx.tree.pointWorld(FrameId(spec.target.frame), spec.target.point).getOrNull()
            ?: return MotionStep.Hold
        val goal = target + (spec.offset?.let { resolver.vec3(it) } ?: Vec3.ZERO)
        val lag = spec.lag?.let { resolver.num(it) } ?: 0.0
        val alpha = 1.0 / (maxOf(0.0, lag) + 1.0)
        val world = MotionMath.lerp(ctx.world.origin, goal, alpha)
        return MotionStep.Move(Frame(MotionMath.worldPointToLocal(ctx, world), ctx.local.rotation))
    }
}
