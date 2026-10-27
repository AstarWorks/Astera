package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/**
 * Return along the straight thread to a (possibly moving) point at `speed` blocks / tick. `ease` "in" ramps the
 * speed up from 25 %, "out" ramps it down toward the end. Arrives when one step covers the rest: the frame is
 * placed on the point and the law finishes with `arrive`.
 */
internal class ReelLaw(
    override val frame: FrameId,
    private val spec: MotionSpec.Reel,
    private val resolver: ValueResolver,
) : MotionLaw {
    override val phase: MotionPhase = MotionPhase.TRANSLATE

    private var startDistance = 0.0

    override fun step(ctx: MotionContext): MotionStep {
        val target = ctx.tree.pointWorld(FrameId(spec.to.frame), spec.to.point).getOrNull()
            ?: return MotionStep.Hold
        val here = ctx.world.origin
        val toTarget: Vec3 = target - here
        val dist = toTarget.length()
        if (startDistance == 0.0) startDistance = maxOf(dist, 1e-9)
        val progress = (1.0 - dist / startDistance).coerceIn(0.0, 1.0)
        val factor = when (spec.ease) {
            "in" -> 0.25 + 0.75 * progress
            "out" -> 0.25 + 0.75 * (1.0 - progress)
            else -> 1.0
        }
        val step = resolver.num(spec.speed) * factor
        val dir = if (dist > 1e-12) toTarget * (1.0 / dist) else Vec3.ZERO
        val arrived = dist <= step
        val world = if (arrived) target else here + dir * step
        val rotation = spec.align?.let { MotionMath.alignedRotation(ctx, it, dir.takeIf { d -> d.lengthSquared() > 0.0 }) }
            ?: ctx.local.rotation
        val next = Frame(MotionMath.worldPointToLocal(ctx, world), rotation)
        return if (arrived) MotionStep.Finish(next, "arrive") else MotionStep.Move(next)
    }
}
