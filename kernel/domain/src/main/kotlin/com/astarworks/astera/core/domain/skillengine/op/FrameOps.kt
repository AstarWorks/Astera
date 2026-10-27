package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec

/** Ops that move or re-parent frames. Family owner: frame + motion worker. */
public sealed interface FrameOp : GameplayOp {

    /** Start a motion law on [frame]: pose / spin / fly / path / follow / stick / reel. */
    public data class Motion(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frame: String,
        val spec: MotionSpec,
    ) : FrameOp

    /** Re-parent [frames] to [to] (keep world pose, or snap). */
    public data class Reparent(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frames: List<String>,
        val to: String,
        val keepWorld: Boolean,
        val snap: SnapSpec?,
    ) : FrameOp

    /** Set the world pose of [frame] equal to the world pose of [like] (plus [offset] in [like]'s frame). */
    public data class Place(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frame: String,
        val like: String,
        val offset: Value<Vec3>?,
    ) : FrameOp
}

/** Re-parent snap: put [point] of the frame on the new parent's origin, sunk [depth] along the contact normal. */
public data class SnapSpec(val point: PointRef?, val depth: Value<Double>?)
