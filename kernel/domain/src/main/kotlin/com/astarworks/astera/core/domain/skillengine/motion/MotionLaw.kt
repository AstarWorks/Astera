package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Composition order is fixed (design 3.3): TRANSLATE laws first, then ROTATE laws. */
public enum class MotionPhase { TRANSLATE, ROTATE }

/** What a law sees on one tick. [env] already resolves params, `pos(...)`, `contact.*` etc. */
public interface MotionContext {
    public val tick: Int
    public val ticksSinceStart: Int
    public val tree: FrameTree
    public val frame: FrameId
    public val local: Frame
    public val world: Frame
    public val env: ExprEnv
}

public sealed interface MotionStep {
    /** Leave the local pose unchanged. */
    public data object Hold : MotionStep

    public data class Move(val local: Frame) : MotionStep

    /** Law ended; optionally set a final pose and raise [event] (e.g. `arrive`, `expire`). */
    public data class Finish(val local: Frame? = null, val event: String? = null) : MotionStep
}

/**
 * A running motion law. Instances are created by the motion runtime from a [MotionSpec] when its op
 * fires, owned by one frame, and stepped once per tick. Laws are the only thing that moves a frame.
 */
public interface MotionLaw {
    public val frame: FrameId
    public val phase: MotionPhase
    public fun step(ctx: MotionContext): MotionStep
}

/**
 * Align a frame: with [to] (pose keys) the local [axis] is turned onto [to]; without it (fly, reel)
 * [axis] is itself the target direction. [up] picks the roll.
 */
public data class AlignSpec(val axis: AxisRef, val up: AxisRef? = null, val to: AxisRef? = null)

public data class RotateKey(val axis: AxisRef, val deg: Value<Double>)

public data class PoseKey(
    val t: Int,
    val rotate: RotateKey? = null,
    /** Position in the PARENT frame's local space. Never put a `pos()` / world point here: use [at]. */
    val offset: Value<Vec3>? = null,
    /** A WORLD point (e.g. `= pos(palm) + up(caster) * 0.9`); converted to the parent-local position. Exclusive with [offset]. */
    val at: Value<Vec3>? = null,
    val align: AlignSpec? = null,
    val ease: String? = null,
)

/** Declarative form of a law, carried by `GameplayOp.Motion`. Fields may be expressions. */
public sealed interface MotionSpec {
    /** Few keys in the parent frame, eased between (pose). */
    public data class Pose(val keys: List<PoseKey>) : MotionSpec

    /** Constant angular speed about [axis] until [untilEvent] (spin). */
    public data class Spin(val axis: AxisRef, val degPerTick: Value<Double>, val untilEvent: String? = null) : MotionSpec

    /** Ballistic flight (fly): block/tick velocity, optional gravity and drag. */
    public data class Fly(
        val velocity: Value<Vec3>,
        val gravity: Value<Double>,
        val drag: Value<Double>,
        val align: AlignSpec?,
        val maxTicks: Value<Double>?,
    ) : MotionSpec

    /** Travel along a named Curve (path). */
    public data class Path(val curve: String, val durationTicks: Value<Double>, val ease: String?, val alignTangent: Boolean) : MotionSpec

    /** Chase a point (follow). */
    public data class Follow(val target: PointRef, val offset: Value<Vec3>?, val lag: Value<Double>?) : MotionSpec

    /** Ride on another frame (stick); the frame must already have been re-parented to it. */
    public data class Stick(val parent: String) : MotionSpec

    /** Return along the thread to [to] (reel); finishes with `arrive`. */
    public data class Reel(val to: PointRef, val speed: Value<Double>, val ease: String?, val align: AlignSpec?) : MotionSpec
}
