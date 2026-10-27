package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import kotlin.random.Random

/**
 * Geometry questions the CEL functions (`pos vel left forward up axis aim_point blade_len rand`) ask.
 * The CEL layer reaches them with `env as? CastEnvQueries`; refs are frame names or `<frame>.<point>`.
 */
public interface CastEnvQueries {
    /** World position of `frame`, `frame.point`, `caster`, `victim` (body centre, F-5). */
    public fun pos(ref: String): Vec3?

    /** Displacement of the frame origin over the last completed tick (blocks per tick). */
    public fun vel(ref: String): Vec3

    /**
     * World direction: `<frame>.forward|up|left|right`, `<frame>.<point axis>` (e.g. `shears.axis`,
     * `blade_a.edge`), `world.up`, `caster.forward` (look direction). Null when unknown.
     */
    public fun axis(name: String): Vec3?

    /** Point [distance] blocks along the caster's look direction from the eye. */
    public fun aimPoint(distance: Double): Vec3

    /** Distance between the `root` and `tip` points of [frame]. */
    public fun bladeLen(frame: String): Double

    /** Deterministic in [0, 1): same cast seed + key + tick gives the same number. */
    public fun rand(key: String): Double
}

/**
 * Variable environment of one cast ([ExprEnv]). Names served:
 * `tick` (ticks since start), every param (after modifiers) and exposed name, `event.name`, `event.source`,
 * `contact.point|normal|rel_velocity` (world, Vec3) and `contact.block` while a contact event is handled,
 * `caster`, `victim` and every frame name (as [ExprValue.Ref]).
 */
public class CastEnv internal constructor(private val cast: CastInstance) : ExprEnv, CastEnvQueries {

    override fun get(name: String): ExprValue? {
        cast.params[name]?.let { return it.toExpr() }
        cast.exposed[name]?.let { return ExprValue.Num(it) }
        when (name) {
            "tick" -> return ExprValue.Num(cast.ticksSinceStart.toDouble())
            "caster" -> return ExprValue.Ref("caster")
            "victim" -> return if (cast.currentVictim() != null) ExprValue.Ref("victim") else null
            "event.name" -> return ExprValue.Str(cast.currentEvent?.name ?: "")
            "event.source" -> return ExprValue.Str(cast.currentEvent?.source ?: "")
        }
        val contact = cast.currentEvent?.contact
        if (contact != null) {
            when (name) {
                "contact.point" -> return ExprValue.Vec3(contact.pointWorld)
                "contact.normal" -> return ExprValue.Vec3(contact.normalWorld)
                "contact.rel_velocity" -> return ExprValue.Vec3(contact.relVelocity)
                "contact.block" -> return ExprValue.Str(contact.part ?: "")
            }
        }
        if (cast.hasNode(name)) return ExprValue.Ref(name)
        return null
    }

    override fun pos(ref: String): Vec3? {
        val frame = ref.substringBefore('.')
        val point = if ('.' in ref) ref.substringAfter('.') else null
        if (frame == "victim") {
            val v = cast.currentVictim() ?: return null
            val root = cast.ports.entities.rootOf(v) ?: return null
            return Vec3(root.feet.x, root.feet.y + root.height * 0.5, root.feet.z)
        }
        return cast.tree.pointWorld(FrameId(frame), point).getOrNull()
    }

    override fun vel(ref: String): Vec3 = cast.lastVelocity(ref.substringBefore('.'))

    override fun axis(name: String): Vec3? {
        if (name == "world.up") return Vec3(0.0, 1.0, 0.0)
        if (name == "caster.forward") return cast.lookDirection()
        if ('.' !in name) return null
        val frame = name.substringBefore('.')
        val part = name.substringAfter('.')
        val world: Frame = cast.tree.worldOf(FrameId(frame)).getOrNull() ?: return null
        return when (part) {
            "forward" -> world.forward()
            "up" -> world.up()
            "left" -> world.left()
            "right" -> world.right()
            else -> cast.tree.point(FrameId(frame), part)?.axis?.let { world.directionToWorld(it).normalize() }
        }
    }

    override fun aimPoint(distance: Double): Vec3 = cast.eye() + cast.lookDirection() * distance

    override fun bladeLen(frame: String): Double {
        val a = cast.tree.pointWorld(FrameId(frame), "root").getOrNull() ?: return 0.0
        val b = cast.tree.pointWorld(FrameId(frame), "tip").getOrNull() ?: return 0.0
        return a.distance(b)
    }

    override fun rand(key: String): Double =
        Random(cast.request.seed xor (key.hashCode().toLong() shl 20) xor cast.tick.toLong()).nextDouble()

    private fun ParamValue.toExpr(): ExprValue = when (this) {
        is ParamValue.Num -> ExprValue.Num(value)
        is ParamValue.Text -> ExprValue.Str(value)
        is ParamValue.Flag -> ExprValue.Bool(value)
    }
}
