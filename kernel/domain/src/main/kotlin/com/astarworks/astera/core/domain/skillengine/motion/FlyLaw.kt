package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/**
 * Ballistic flight in WORLD space (blocks / tick): each tick `v = (v - (0, gravity, 0)) * (1 - drag)`, then
 * the frame moves by `v`. With `align` the orientation is `Basis.facing(axis, up)` (the BASE orientation, a
 * spin multiplies on top of it). Ends with `expire` after `maxTicks`.
 */
internal class FlyLaw(
    override val frame: FrameId,
    private val spec: MotionSpec.Fly,
    private val resolver: ValueResolver,
) : MotionLaw {
    override val phase: MotionPhase = MotionPhase.TRANSLATE

    private var velocity: Vec3 = resolver.vec3(spec.velocity)
    private var age = 0

    override fun step(ctx: MotionContext): MotionStep {
        val gravity = resolver.num(spec.gravity)
        val keep = 1.0 - resolver.num(spec.drag)
        velocity = Vec3(velocity.x * keep, (velocity.y - gravity) * keep, velocity.z * keep)
        val origin = MotionMath.worldPointToLocal(ctx, ctx.world.origin + velocity)
        val rotation = spec.align?.let { MotionMath.alignedRotation(ctx, it, velocity) } ?: ctx.local.rotation
        val next = Frame(origin, rotation)
        age++
        val max = spec.maxTicks?.let { resolver.num(it) }
        return if (max != null && age >= max) MotionStep.Finish(next, "expire") else MotionStep.Move(next)
    }
}
