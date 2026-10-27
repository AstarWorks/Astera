package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.skillengine.frame.FrameId

/**
 * Ride on another frame: replaces the frame's other TRANSLATE law and holds the local pose. If the frame is
 * not yet under [MotionSpec.Stick.parent] it is re-parented there keeping its world pose.
 */
internal class StickLaw(
    override val frame: FrameId,
    private val spec: MotionSpec.Stick,
) : MotionLaw {
    override val phase: MotionPhase = MotionPhase.TRANSLATE

    override fun step(ctx: MotionContext): MotionStep {
        val parent = FrameId(spec.parent)
        if (ctx.tree.node(frame)?.parent != parent) {
            ctx.tree.reparent(frame, parent, keepWorld = true)
            val local = ctx.tree.node(frame)?.local
            if (local != null) return MotionStep.Move(local)
        }
        return MotionStep.Hold
    }
}
