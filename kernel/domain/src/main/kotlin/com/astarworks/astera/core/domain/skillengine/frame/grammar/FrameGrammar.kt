package com.astarworks.astera.core.domain.skillengine.frame.grammar

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import kotlin.math.abs

/**
 * Frame grammar (ADR-0314): a frame is an origin and three vectors `v`, `w`, `s`. Everything a skill draws (a particle, a model, a plane,
 * a line, the held weapon) is placed by coefficients of those vectors, and a frame moves by keyed `move` / `rotate` / `scale` tracks.
 * Pure: [FrameEvaluator] turns one [FrameDecl] and the per-tick [FrameInputs] into one [FrameState]; the runtime evaluates every live
 * frame once per tick and every reader shares that table.
 */

/**
 * Where a frame's axes sit in the local space of a thing oriented by [FrameState.orientation] (`Basis.facing(v, w)`): local z is `v`,
 * local y is `w`, local x is `-s` (`s = v x w` points to the local right). A display riding a frame turns about these; its sprite plate
 * (local x-y) is placed with `plane: [a, b]` = width along `a`, height along `b`.
 */
public object FrameLocal {
    public val V: Vec3 = Vec3(0.0, 0.0, 1.0)
    public val W: Vec3 = Vec3(0.0, 1.0, 0.0)
    public val S: Vec3 = Vec3(-1.0, 0.0, 0.0)

    /** `v`, `w`, `s` or their `-` negation; null for any other name. */
    public fun axis(name: String): Vec3? {
        val neg = name.startsWith("-")
        val base = when (name.removePrefix("-")) {
            "v" -> V
            "w" -> W
            "s" -> S
            else -> return null
        }
        return if (neg) base * -1.0 else base
    }
}

/**
 * Coefficients along `v`, `w`, `s`. A component written as a CEL expression (`pos: {v: "= i * 0.2"}`) keeps its source in [exprs]
 * (index 0/1/2 = v/w/s; null = a numeric component) and its numeric slot is 0; the player evaluates the expression each tick.
 */
public data class Coeff(val v: Double = 0.0, val w: Double = 0.0, val s: Double = 0.0, val exprs: List<String?>? = null) {
    public companion object {
        public val ZERO: Coeff = Coeff()
        public val ONE: Coeff = Coeff(1.0, 1.0, 1.0)
    }
}

/**
 * One point of a declared frame: `{frame: <id>, pos: {v, w, s}}` (ADR-0314). The one point type every op that places or ends on a frame
 * reads (an area `target`, a ribbon end, a line end): the frame's origin plus [pos] along its axes, evaluated per tick.
 */
public data class FramePoint(val frame: String, val pos: Coeff = Coeff.ZERO)

/** Three vectors, not required to be orthogonal or unit; only non-degenerate (they span a volume). */
public data class Basis3(val v: Vec3, val w: Vec3, val s: Vec3) {
    /** Signed volume of the parallelepiped: zero = coplanar. */
    public val determinant: Double get() = v.dot(w.cross(s))

    public val isDegenerate: Boolean get() = abs(determinant) < DEGENERATE_EPS

    /** World point of [c] from [origin]. */
    public fun point(origin: Vec3, c: Coeff): Vec3 = origin + v * c.v + w * c.w + s * c.s

    /** World direction of [c] (no origin). */
    public fun direction(c: Coeff): Vec3 = v * c.v + w * c.w + s * c.s

    public fun rotated(q: Quaternion): Basis3 = Basis3(q.rotate(v), q.rotate(w), q.rotate(s))

    public fun scaled(c: Coeff): Basis3 = Basis3(v * c.v, w * c.w, s * c.s)

    /**
     * The orthonormal orientation a display takes: local +Z = `v`, local +Y = `w` with its component along `v` removed, +X = Y x Z.
     * A skew basis (non-orthogonal `v`, `w`) cannot be drawn by a Display entity, which only takes a rotation: the skew is lost here.
     */
    public fun orientation(): Quaternion = Basis.facing(v, w)

    public fun axis(name: String): Vec3? = when (name) {
        "v" -> v
        "w" -> w
        "s" -> s
        else -> null
    }

    public companion object {
        public const val DEGENERATE_EPS: Double = 1e-6
    }
}

/** One evaluated frame: where it is this tick. */
public data class FrameState(val origin: Vec3, val basis: Basis3) {
    public fun world(c: Coeff): Vec3 = basis.point(origin, c)
    public fun orientation(): Quaternion = basis.orientation()
}

/** Where the origin of a frame is read from each tick. */
public sealed interface OriginSpec {
    /** A caster point: `caster`, `caster.eye`, `caster.hand_r`, `caster.hand_l`, `caster.shoulder_r`, `caster.shoulder_l`, `caster.feet`. */
    public data class Caster(val point: String) : OriginSpec

    /** A live instance of the skill (a projectile body, a frame, a rig). */
    public data class Instance(val name: String) : OriginSpec

    /**
     * A point of another frame of this skill, `origin: {frame: <id>, pos: {v, w, s}}`: the parent's origin plus [pos] along the parent's axes
     * this tick. A child of a rotating parent therefore stays [pos]-far from the parent's origin on every tick (an arm frame rooted on the
     * shoulder, a blade frame at its reach). The parent is evaluated first; a cycle is a load error.
     */
    public data class Frame(val frame: String, val pos: Coeff) : OriginSpec

    /** The victim the frame is instantiated for (`on: event.victim`); [point] = `center` (default), `eye`, `feet`, `body`. */
    public data class Victim(val point: String) : OriginSpec

    /** The entity that hit the caster (`event.attacker` of the event in scope; `on: event.attacker` or `on: state.<name>`), at a victim-style point. */
    public data class Attacker(val point: String) : OriginSpec

    /**
     * The aim point (`origin: {aim: <range>}`): a ray from `caster.eye` along the look direction; the origin is the first block it hits within
     * [range] blocks, else the point at [range]. The same semantics as the `aim_point(caster, range)` expression; [range] is a number, `${param}` or `= expr`. `stops: entities` also ends the ray at the entry point of the first enemy hitbox it meets (the old `ray_stops: entities`).
     */
    public data class Aim(val range: com.astarworks.astera.core.domain.skillengine.model.Value<Double>, val stopsAtEntities: Boolean = false) : OriginSpec

    /** The contact the frame is instantiated for (`on: event.block`: where the projectile struck the block; `on: event.victim`: the hit point of the event, the old `anchor: contact`). */
    public data object Contact : OriginSpec
}

/** How one axis of a frame is read each tick, before rotation and scale. */
public sealed interface AxisSpec {
    /** A named world direction: `aim`, `up`, `head.up` (world up, turning smoothly to the caster's head-up as the look nears vertical, so `v: aim, w: head.up` has volume at pitch +-90), `forward`, `left`, `incoming`, `velocity`, `down`, `back`, `right`. */
    public data class Named(val name: String) : AxisSpec

    /** The cross product of two of this frame's own base axes (`v x w`, `w x s`, `s x v`, ...), read after both are resolved. */
    public data class Cross(val a: String, val b: String) : AxisSpec

    /** A literal world vector. */
    public data class Literal(val vector: Vec3) : AxisSpec

    public companion object {
        public val NAMED: Set<String> = setOf("aim", "up", "head.up", "down", "forward", "back", "left", "right", "incoming", "incoming.feet", "velocity", "normal", "body.forward", "body.back", "body.up", "body.down", "body.left", "body.right")
    }
}

/**
 * `rotate:` key: at tick [at], over [forTicks] ticks, the angle about the frame's own axis [about] goes from [from] (null = carried) to
 * [to] degrees. A reset key ([about] null, written `reset: true`) turns the whole chain back to the rest pose over [forTicks] instead:
 * body rotations do not commute, so "every angle back to 0" is not the rest pose; the reset is.
 */
public data class RotateKey(val at: Int, val forTicks: Int, val about: String?, val from: Double?, val to: Double, val ease: Easing) {
    public val isReset: Boolean get() = about == null

    public companion object {
        public fun reset(at: Int, forTicks: Int, ease: Easing): RotateKey = RotateKey(at, forTicks, null, null, 0.0, ease)
    }
}

/** `move:` key: at tick [at], over [forTicks] ticks, the origin offset goes to [to] (coefficients of the DECLARED axes, before any `rotate` / `scale` key: the origin path is the caster-relative path of the hand, whatever the blade is doing about it, ADR-0315). */
public data class MoveKey(val at: Int, val forTicks: Int, val to: Coeff, val ease: Easing)

/** `scale:` key: at tick [at], over [forTicks] ticks, the axis lengths go to [to] (1 = as read). */
public data class ScaleKey(val at: Int, val forTicks: Int, val to: Coeff, val ease: Easing)

/** One `frames:` entry. */
public data class FrameDecl(
    val id: String,
    val origin: OriginSpec,
    val v: AxisSpec,
    val w: AxisSpec,
    val s: AxisSpec,
    val rotate: List<RotateKey> = emptyList(),
    val move: List<MoveKey> = emptyList(),
    val scale: List<ScaleKey> = emptyList(),
    /**
     * `on: event.victim` / `event.block` / `event.attacker`: one instance per victim / block contact / attacker of the event in scope (`each: true`),
     * rooted on it. `on: state.<name>`: one instance per entry of that state (no `each`). Either way the `move:` / `rotate:` / `scale:` keys count
     * their `at:` from the tick of the event / the state entry that made the instance, not from the cast start.
     */
    val on: String? = null,
    val each: Boolean = false,
    /**
     * `snapshot: cast | <tick>`: the origin and the named directions are read once, at this tick of the frame's life (`cast` = 0), and held in
     * world space from then on; `move` / `rotate` / `scale` keys still run on the frozen frame. Null = the frame follows its origin every tick.
     */
    val snapshotAt: Int? = null,
) {
    /** `on: event.victim`: one instance per hit victim, rooted on that victim. */
    val perVictim: Boolean get() = on == ON_VICTIM

    /** `on: event.block`: one instance per block contact, rooted on the contact point. */
    val perBlock: Boolean get() = on == ON_BLOCK

    /** `on: event.attacker`: one instance per attacker, rooted on that attacker. */
    val perAttacker: Boolean get() = on == ON_ATTACKER

    /** Instantiated by an event binding (a victim, a block contact or an attacker) at the first row that reads it, not at cast start. */
    val perEvent: Boolean get() = on != null && on.startsWith("event.")

    /** `on: state.<name>`: one instance per entry of the state, its clock starting on the entry tick. */
    val perState: Boolean get() = on != null && on.startsWith(STATE_PREFIX)

    /** The state name of an `on: state.<name>` frame. */
    val stateName: String? get() = on?.takeIf { it.startsWith(STATE_PREFIX) }?.removePrefix(STATE_PREFIX)

    /** Whether the state at [path] (`cast/riposte`) is the one an `on: state.<name>` frame names: the whole path or its last segment. */
    fun startsOnState(path: String): Boolean = stateName?.let { path == it || path.substringAfterLast('/') == it } == true

    public companion object {
        /**
         * The frame a callee receives through `frames_in: {name: frame}`: rooted on the caller's frame the call (or `summon`) binds to [name], with
         * the legacy placement axes (v = forward, w = up, s = left). A call renames it to the caller's frame id; a `summon` hands the callee an instance of that name.
         */
        public fun inbound(name: String): FrameDecl = FrameDecl(name, OriginSpec.Instance(name), AxisSpec.Named("forward"), AxisSpec.Named("up"), AxisSpec.Named("left"))

        public const val ON_VICTIM: String = "event.victim"
        public const val ON_BLOCK: String = "event.block"
        public const val ON_ATTACKER: String = "event.attacker"
        public const val STATE_PREFIX: String = "state."
    }
}

/**
 * `lines: {<id>: {frame, from, to}}` (ADR-0314): a named segment of one frame, between two coefficient points. A draw op reads it with
 * `along: <id>` + `samples: n` and emits at n points spread from [from] to [to], ends included - the blade edge is written once and
 * every trail, gather and sheath row names it. The two ends may sit on different frames ([FramePoint.frame] of [from] != of [to]): a beam or ribbon between
 * two moving points, written `from: {frame: a, pos: {...}}` / `to: {frame: b, pos: {...}}`.
 */
public data class LineDecl(val id: String, val from: FramePoint, val to: FramePoint)

/** What the runtime reads for one frame instance at one tick. */
public class FrameInputs(
    public val origin: Vec3,
    /** World direction per [AxisSpec.Named] name. */
    public val direction: (String) -> Vec3?,
)

/** Pure evaluation of a frame at tick [t] (ticks since the instance started). */
public object FrameEvaluator {
    public sealed interface Outcome {
        public data class Ok(val state: FrameState) : Outcome
        public data class Degenerate(val field: String, val message: String) : Outcome
    }

    public fun evaluate(decl: FrameDecl, t: Int, inputs: FrameInputs): Outcome {
        val base = baseAxes(decl, inputs) ?: return Outcome.Degenerate("v", "frame '${decl.id}': an axis direction is unreadable this tick")
        if (base.isDegenerate) return Outcome.Degenerate("s", "frame '${decl.id}': v, w, s are coplanar (determinant ${base.determinant}); the frame has no volume")
        val rot = rotationAt(decl, base, t)
        val turned = base.rotated(rot).scaled(scaleAt(decl, t))
        val origin = base.point(inputs.origin, moveAt(decl, t))
        return Outcome.Ok(FrameState(origin, turned))
    }

    /** The base axes before any key (unit-less: a literal keeps its length, a named direction is unit). */
    public fun baseAxes(decl: FrameDecl, inputs: FrameInputs): Basis3? {
        val resolved = HashMap<String, Vec3>()
        fun plain(name: String, spec: AxisSpec): Vec3? = when (spec) {
            is AxisSpec.Literal -> spec.vector
            is AxisSpec.Named -> inputs.direction(spec.name)
            is AxisSpec.Cross -> null
        }
        for ((name, spec) in listOf("v" to decl.v, "w" to decl.w, "s" to decl.s)) plain(name, spec)?.let { resolved[name] = it }
        for ((name, spec) in listOf("v" to decl.v, "w" to decl.w, "s" to decl.s)) {
            if (spec is AxisSpec.Cross) {
                val a = resolved[spec.a] ?: return null
                val b = resolved[spec.b] ?: return null
                resolved[name] = a.cross(b)
            }
        }
        val v = resolved["v"] ?: return null
        val w = resolved["w"] ?: return null
        val s = resolved["s"] ?: return null
        return Basis3(v, w, s)
    }

    /**
     * Rotation at [t]: every key turns the frame about one of its OWN axes (`v`, `w` or `s` as they stand after the keys listed before it)
     * from its start angle to `to`, keys composed in list order as body rotations. A key's start is its `from`, else the angle the
     * previous key about the same axis ended at (else 0), so keys chain and the end pose is carried; a key with `to: 0` interpolates the
     * reset. Before `at` the key holds its start angle (an explicit `from` is a pose, not a jump mid-key); at `at` it begins, so a
     * `for: 0` step is the value AT its own tick (a `{at: 0, for: 0}` key places a frame on the cast tick, ADR-0315).
     *
     * Body axes make the cut plane a construction, not a number: a weapon rides a frame with its length along `v` and its edge along
     * `w`, so a key `about: s` moves the tip along the edge (edge-leading) whatever earlier keys (a roll about `v`, a yaw about `w`) did
     * to pick the plane.
     */
    public fun rotationAt(decl: FrameDecl, base: Basis3, t: Int): Quaternion {
        if (decl.rotate.isEmpty()) return Quaternion.IDENTITY
        val carried = HashMap<String, Double>()
        var q = Quaternion.IDENTITY
        for (k in decl.rotate) {
            val about = k.about
            if (about == null) {
                // reset: the chain so far eases back to the rest pose; every key after it starts from rest
                carried.clear()
                q = when {
                    t < k.at -> q
                    k.forTicks <= 0 || t >= k.at + k.forTicks -> Quaternion.IDENTITY
                    else -> Quaternion.slerp(q, Quaternion.IDENTITY, k.ease.apply((t - k.at).toDouble() / k.forTicks))
                }
                continue
            }
            val before = carried[about] ?: 0.0
            val start = k.from ?: before
            val angle = angleOf(k, start, t)
            carried[about] = k.to
            val delta = angle - before
            if (delta == 0.0) continue
            val axis = base.axis(about) ?: continue
            // q * r turns about the frame's current axis (q rotates the base axis into place: q r q^-1 is that world-axis rotation)
            q = q * Quaternion.axisAngle(axis, Math.toRadians(delta))
        }
        return q
    }

    /** The angle (degrees) key [k] is at when tick [t] is seen, given its start angle. */
    public fun angleOf(k: RotateKey, start: Double, t: Int): Double = when {
        t < k.at -> start
        k.forTicks <= 0 || t >= k.at + k.forTicks -> k.to
        else -> start + (k.to - start) * k.ease.apply((t - k.at).toDouble() / k.forTicks)
    }

    public fun moveAt(decl: FrameDecl, t: Int): Coeff = track(decl.move.map { Key(it.at, it.forTicks, it.to, it.ease) }, t, Coeff.ZERO)

    public fun scaleAt(decl: FrameDecl, t: Int): Coeff = track(decl.scale.map { Key(it.at, it.forTicks, it.to, it.ease) }, t, Coeff.ONE)

    private class Key(val at: Int, val forTicks: Int, val to: Coeff, val ease: Easing)

    /** Keys in list order: each holds until its `at`, eases to its `to` over `for` (a `for: 0` step is `to` from `at` on), then the next key starts from there. */
    private fun track(keys: List<Key>, t: Int, start: Coeff): Coeff {
        var cur = start
        for (k in keys) {
            if (t < k.at) return cur
            if (k.forTicks <= 0 || t >= k.at + k.forTicks) {
                cur = k.to
                continue
            }
            val u = k.ease.apply((t - k.at).toDouble() / k.forTicks)
            return Coeff(cur.v + (k.to.v - cur.v) * u, cur.w + (k.to.w - cur.w) * u, cur.s + (k.to.s - cur.s) * u)
        }
        return cur
    }
}
