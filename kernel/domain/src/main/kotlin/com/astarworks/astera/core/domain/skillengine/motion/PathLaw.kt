package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Curve
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/**
 * Travel along a Curve. The curve lives in the pose the frame had at the first step (its origin and
 * orientation, in the parent frame), so a curve is reusable. Ends with `arrive`; a missing curve ends at once
 * with `path-missing:<name>` so the failure is visible in the cast trace.
 */
internal class PathLaw(
    override val frame: FrameId,
    private val spec: MotionSpec.Path,
    private val curve: Curve?,
    private val resolver: ValueResolver,
) : MotionLaw {
    override val phase: MotionPhase = MotionPhase.TRANSLATE

    private var start: Frame? = null

    override fun step(ctx: MotionContext): MotionStep {
        if (curve == null) return MotionStep.Finish(event = "path-missing:${spec.curve}")
        val origin = start ?: ctx.local.also { start = it }
        val duration = maxOf(1.0, resolver.num(spec.durationTicks))
        val raw = (ctx.ticksSinceStart + 1) / duration
        val t = MotionMath.ease(spec.ease, raw)
        val pos = origin.toWorld(curve.point(t))
        val rot: Quaternion = if (spec.alignTangent) {
            val tan = curve.tangent(t)
            if (tan.lengthSquared() > 0.0) Basis.facing(origin.directionToWorld(tan)) else origin.rotation
        } else {
            origin.rotation
        }
        val next = Frame(pos, rot)
        return if (raw >= 1.0) MotionStep.Finish(next, "arrive") else MotionStep.Move(next)
    }
}
