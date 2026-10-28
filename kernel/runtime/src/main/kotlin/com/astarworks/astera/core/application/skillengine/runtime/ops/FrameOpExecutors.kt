package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutorRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.op.FrameOp
import kotlin.reflect.KClass

private val ORIGIN = Vec3(0.0, 0.0, 0.0)

/** World pose of [id], or null after reporting the failure on the host. */
private fun worldOrFail(ctx: OpContext, opId: String, id: FrameId): Frame? =
    when (val r = ctx.tree.worldOf(id)) {
        is Result.Success -> r.value
        is Result.Failure -> {
            ctx.host.fail(opId, "frame '${id.value}': ${r.error}")
            null
        }
    }

/** Pose of [world] expressed in the frame [parentWorld] (inverse of [Frame.compose]). */
private fun relativeTo(parentWorld: Frame, world: Frame): Frame =
    Frame(
        parentWorld.rotation.conjugate().rotate(world.origin - parentWorld.origin),
        (parentWorld.rotation.conjugate() * world.rotation).normalized(),
    )

private fun FrameTree.worldOrIdentity(id: FrameId?): Frame? =
    if (id == null) Frame(ORIGIN) else (worldOf(id) as? Result.Success)?.value

/** pose / spin / fly / path / follow / stick / reel: hand the spec to the runner's motion stepping. */
public class MotionOpExecutor : OpExecutor<FrameOp.Motion> {
    override val opClass: KClass<FrameOp.Motion> = FrameOp.Motion::class

    override fun execute(op: FrameOp.Motion, ctx: OpContext) {
        if (ctx.tree.node(FrameId(op.frame)) == null) {
            ctx.host.fail(op.id, "motion on unknown frame '${op.frame}'")
            return
        }
        ctx.host.startMotion(FrameId(op.frame), op.spec)
    }
}

/**
 * Re-parent keeps the world pose (`newParent.world.inverse * node.world`) unless a [FrameOp.Reparent.snap]
 * is given: then the frame keeps its world rotation and the snap point lands on the new parent's
 * origin, sunk `depth` into the surface against the contact normal.
 */
public class ReparentOpExecutor : OpExecutor<FrameOp.Reparent> {
    override val opClass: KClass<FrameOp.Reparent> = FrameOp.Reparent::class

    override fun execute(op: FrameOp.Reparent, ctx: OpContext) {
        val newParent = FrameId(op.to)
        for (name in op.frames) {
            val id = FrameId(name)
            val result = if (op.snap == null) {
                ctx.tree.reparent(id, newParent, keepWorld = op.keepWorld, snap = null)
            } else {
                val snapFrame = snapPose(op, name, id, newParent, ctx) ?: continue
                ctx.tree.reparent(id, newParent, keepWorld = false, snap = snapFrame)
            }
            if (result is Result.Failure) ctx.host.fail(op.id, "reparent '$name' -> '${op.to}': ${result.error}")
        }
    }

    private fun snapPose(op: FrameOp.Reparent, name: String, id: FrameId, newParent: FrameId, ctx: OpContext): Frame? {
        val snap = op.snap ?: return null
        val nodeWorld = worldOrFail(ctx, op.id, id) ?: return null
        val parentWorld = worldOrFail(ctx, op.id, newParent) ?: return null
        val relRotation = (parentWorld.rotation.conjugate() * nodeWorld.rotation).normalized()

        // The snapped point, in the moved frame's own coordinates (it may belong to another frame: `self.root`).
        val pointLocal = snap.point?.let { ref ->
            val owner = FrameId(if (ref.frame == "self") name else ref.frame)
            val world = when (val r = ctx.tree.pointWorld(owner, ref.point)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    ctx.host.fail(op.id, "snap point '$ref': ${r.error}")
                    return null
                }
            }
            nodeWorld.rotation.conjugate().rotate(world - nodeWorld.origin)
        } ?: ORIGIN

        val depth = snap.depth?.let { ctx.values.num(it) } ?: 0.0
        val sunk = ctx.host.contactNormal
            ?.let { parentWorld.rotation.conjugate().rotate(it).normalize() * -depth }
            ?: ORIGIN
        return Frame(sunk - relRotation.rotate(pointLocal), relRotation)
    }
}

/** Set a frame's world pose to another frame's (plus an offset in that frame's axes). */
public class PlaceOpExecutor : OpExecutor<FrameOp.Place> {
    override val opClass: KClass<FrameOp.Place> = FrameOp.Place::class

    override fun execute(op: FrameOp.Place, ctx: OpContext) {
        val id = FrameId(op.frame)
        val node = ctx.tree.node(id)
        if (node == null) {
            ctx.host.fail(op.id, "place on unknown frame '${op.frame}'")
            return
        }
        val like = worldOrFail(ctx, op.id, FrameId(op.like)) ?: return
        val offset = op.offset?.let { ctx.values.vec3(it) } ?: ORIGIN
        val origin = like.toWorld(offset)
        val orient = op.orient
        val rotation = if (orient == null) like.rotation else {
            val look = worldOrFail(ctx, op.id, FrameId(orient.look)) ?: return
            orient.rotation(origin, look, ctx.tree.worldOf(FrameId(orient.reference)).getOrNull())
        }
        val target = Frame(origin, rotation)
        val parentWorld = ctx.tree.worldOrIdentity(node.parent)
        if (parentWorld == null) {
            ctx.host.fail(op.id, "place: parent of '${op.frame}' has no world pose")
            return
        }
        val r = ctx.tree.setLocal(id, relativeTo(parentWorld, target))
        if (r is Result.Failure) ctx.host.fail(op.id, "place '${op.frame}': ${r.error}")
    }
}

public fun registerFrameOpExecutors(registry: OpExecutorRegistry) {
    registry.register(MotionOpExecutor())
    registry.register(ReparentOpExecutor())
    registry.register(PlaceOpExecutor())
}
