package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/**
 * Constant angular speed about an axis. The axis is resolved once on the first step (world space) and kept
 * in the body's base-local frame, so `rotation = base * axisAngle(localAxis, accumulatedDeg)`: with a
 * horizontal axis perpendicular to the throw the rings stay in the vertical plane that holds the throw direction.
 */
internal class SpinLaw(
    override val frame: FrameId,
    private val spec: MotionSpec.Spin,
    private val resolver: ValueResolver,
) : MotionLaw, EventBoundLaw, RotationContribution {
    override val phase: MotionPhase = MotionPhase.ROTATE
    override val untilEvent: String? get() = spec.untilEvent
    override var contribution: Quaternion = Quaternion.IDENTITY
        private set

    private var localAxis: Vec3? = null
    private var accumulatedDeg = 0.0

    override fun step(ctx: MotionContext): MotionStep {
        var axis = localAxis
        if (axis == null) {
            val world = MotionAxes.resolve(spec.axis, ctx) ?: return MotionStep.Hold
            axis = ctx.local.rotation.conjugate().rotate(MotionMath.worldDirToLocal(ctx, world)).normalize()
            localAxis = axis
        }
        accumulatedDeg += resolver.num(spec.degPerTick)
        contribution = Quaternion.axisAngle(axis, Math.toRadians(accumulatedDeg))
        return MotionStep.Move(Frame(ctx.local.origin, (ctx.local.rotation * contribution).normalized()))
    }
}
