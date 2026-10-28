package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.frame.Axes
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.AxisRef

/** An event a law raised when it finished (`arrive`, `expire`, ...). */
public data class MotionEvent(val frame: FrameId, val event: String)

/** A law that stops when the named cast event is raised via [MotionComposer.raise] (spin `until`). */
public interface EventBoundLaw : MotionLaw {
    public val untilEvent: String?
}

/**
 * A ROTATE law that multiplies a rotation onto the base orientation. The composer needs it to strip the
 * contribution again when the tree changed under it (re-parent keepWorld), so spin never double-counts.
 */
public interface RotationContribution {
    public val contribution: Quaternion
}

/** Shared math of the laws. */
internal object MotionMath {
    private val IDENTITY = Frame(Vec3.ZERO)

    /** World direction of an axis name ([Axes]); the cast's `velocity` variable is the default velocity. */
    fun axis(name: AxisRef, ctx: MotionContext, velocity: Vec3? = null): Vec3? =
        Axes.resolve(ctx.tree, name.name, velocity ?: (ctx.env.variables["velocity"] as? ExprValue.Vec3)?.value)

    fun parentWorld(ctx: MotionContext): Frame =
        ctx.tree.node(ctx.frame)?.parent?.let { ctx.tree.worldOf(it).getOrNull() } ?: IDENTITY

    fun worldPointToLocal(ctx: MotionContext, world: Vec3): Vec3 {
        val p = parentWorld(ctx)
        return p.rotation.conjugate().rotate(world - p.origin)
    }

    fun worldDirToLocal(ctx: MotionContext, dir: Vec3): Vec3 =
        parentWorld(ctx).rotation.conjugate().rotate(dir)

    /** Local (parent-space) orientation whose forward points along [align].axis; null if the axis is unresolvable. */
    fun alignedRotation(ctx: MotionContext, align: AlignSpec, velocity: Vec3?): Quaternion? {
        val dir = axis(align.axis, ctx, velocity) ?: return null
        val up = align.up?.let { axis(it, ctx, velocity) } ?: Vec3(0.0, 1.0, 0.0)
        val worldRot = Basis.facing(dir, up)
        return (parentWorld(ctx).rotation.conjugate() * worldRot).normalized()
    }

    fun ease(name: String?, t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (name) {
            "in" -> x * x
            "out" -> 1.0 - (1.0 - x) * (1.0 - x)
            "inOut", "smooth" -> x * x * (3.0 - 2.0 * x)
            else -> x
        }
    }

    fun lerp(a: Vec3, b: Vec3, t: Double): Vec3 = a + (b - a) * t
}

/**
 * Steps the running laws of one cast. Per frame and tick, TRANSLATE laws run first, ROTATE laws after
 * (design 3.3). A frame's rotation is kept as `base` (what translate laws / `fly align` define) so a ROTATE
 * law such as spin can multiply onto it every tick without feeding back into itself. A new TRANSLATE law
 * replaces the frame's previous TRANSLATE law; a new ROTATE law replaces the previous one of its class.
 */
public class MotionComposer(
    private val tree: FrameTree,
    private val baseEnv: (FrameId) -> ExprEnv = { EmptyEnv },
    private val frozen: (FrameId) -> Boolean = { false },
) {
    private class Active(val law: MotionLaw, val startTick: Int)

    private class FrameState {
        val laws = ArrayList<Active>()
        var base: Quaternion? = null
        var lastWritten: Frame? = null
        var lastWorldOrigin: Vec3? = null
        var velocity: Vec3 = Vec3.ZERO
    }

    private val states = LinkedHashMap<FrameId, FrameState>()

    public fun start(law: MotionLaw, tick: Int) {
        val st = states.getOrPut(law.frame) { FrameState() }
        st.laws.removeAll { it.law.phase == law.phase && (law.phase == MotionPhase.TRANSLATE || it.law::class == law::class) }
        st.laws.add(Active(law, tick))
    }

    public fun stop(frame: FrameId) {
        states.remove(frame)
    }

    public fun stop(frame: FrameId, phase: MotionPhase) {
        val st = states[frame] ?: return
        st.laws.removeAll { it.law.phase == phase }
        absorbContribution(st)
    }

    /** True while any law is still running (a cast with live laws is not idle). */
    public fun hasWork(): Boolean = states.values.any { it.laws.isNotEmpty() }

    public fun isMoving(frame: FrameId): Boolean = states[frame]?.laws?.isNotEmpty() == true

    /** Stop every law bound to [event] (spin `until`). */
    public fun raise(event: String) {
        for (st in states.values) {
            st.laws.removeAll { (it.law as? EventBoundLaw)?.untilEvent == event }
            absorbContribution(st)
        }
    }

    public fun tick(tick: Int): List<MotionEvent> {
        val events = ArrayList<MotionEvent>()
        for (frame in states.keys.toList()) {
            val st = states[frame] ?: continue
            if (frozen(frame)) continue
            val node = tree.node(frame)
            if (node == null || st.laws.isEmpty()) {
                states.remove(frame)
                continue
            }
            val local = node.local
            if (st.lastWritten != local) {
                val contrib = st.laws.mapNotNull { (it.law as? RotationContribution)?.contribution }
                    .fold(Quaternion.IDENTITY) { acc, q -> acc * q }
                st.base = (local.rotation * contrib.conjugate()).normalized()
            }
            var cur = Frame(local.origin, st.base ?: local.rotation)
            val parentWorld = node.parent?.let { tree.worldOf(it).getOrNull() } ?: Frame(Vec3.ZERO)
            val env = frameEnv(baseEnv(frame), st.velocity)

            for (phase in MotionPhase.entries) {
                if (phase == MotionPhase.ROTATE) st.base = cur.rotation
                for (active in st.laws.filter { it.law.phase == phase }) {
                    val ctx = Ctx(tick, tick - active.startTick, tree, frame, cur, parentWorld.compose(cur), env)
                    when (val step = active.law.step(ctx)) {
                        MotionStep.Hold -> {}
                        is MotionStep.Move -> cur = step.local
                        is MotionStep.Finish -> {
                            step.local?.let { cur = it }
                            step.event?.let { events.add(MotionEvent(frame, it)) }
                            st.laws.remove(active)
                        }
                    }
                }
            }
            if (st.laws.none { it.law is RotationContribution }) st.base = cur.rotation

            tree.setLocal(frame, cur)
            st.lastWritten = cur
            val worldOrigin = tree.worldOf(frame).getOrNull()?.origin
            if (worldOrigin != null) {
                st.velocity = st.lastWorldOrigin?.let { worldOrigin - it } ?: Vec3.ZERO
                st.lastWorldOrigin = worldOrigin
            }
            if (st.laws.isEmpty()) states.remove(frame)
        }
        return events
    }

    /** When no rotation-contributing law is left, what was last written becomes the new base. */
    private fun absorbContribution(st: FrameState) {
        if (st.laws.none { it.law is RotationContribution }) st.lastWritten?.let { st.base = it.rotation }
    }

    private fun frameEnv(base: ExprEnv, velocity: Vec3): ExprEnv =
        if (velocity.lengthSquared() > 0.0) ExprEnv(base.variables + ("velocity" to ExprValue.Vec3(velocity)), base.frames, base.seed, base.host, base.castSeed) else base

    private class Ctx(
        override val tick: Int,
        override val ticksSinceStart: Int,
        override val tree: FrameTree,
        override val frame: FrameId,
        override val local: Frame,
        override val world: Frame,
        override val env: ExprEnv,
    ) : MotionContext
}


private object NoFrames : FrameLookup {
    override fun worldPose(frame: String, point: String?): Frame? = null
    override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
}

private val EmptyEnv = ExprEnv(emptyMap(), NoFrames)
