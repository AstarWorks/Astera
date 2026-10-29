package com.astarworks.astera.core.domain.skillengine.model

/**
 * A numeric / vector / quaternion / bool value that may be a literal, a `${param}` reference or an
 * `= <CEL expr>` (F-7). Expressions are compiled once at load by the application layer; this type
 * only carries the source text, so the model stays pure.
 */
public sealed interface Value<out T> {
    public data class Literal<out T>(val value: T) : Value<T>
    public data class ParamRef(val name: String) : Value<Nothing>
    public data class Expr(val source: String) : Value<Nothing>
}

public fun <T> literal(value: T): Value<T> = Value.Literal(value)

/** Name of a frame declared in `frames:` (or a built-in: world, caster, enemy, contact). */
@JvmInline
public value class FrameName(public val value: String)

/**
 * A named point: `<frame>.<point>`, e.g. `blade_a.tip`, `caster.right_hand`, `contact.point`.
 * A bare frame name (no dot) means the frame origin.
 */
public data class PointRef(val frame: String, val point: String? = null) {
    override fun toString(): String = if (point == null) frame else "$frame.$point"

    public companion object {
        public fun parse(text: String): PointRef {
            val i = text.indexOf('.')
            return if (i < 0) PointRef(text) else PointRef(text.substring(0, i), text.substring(i + 1))
        }
    }
}

/** Named direction resolved by the runtime: `world.up`, `velocity`, `shears.axis`, `line.along`. */
@JvmInline
public value class AxisRef(public val name: String)

/** Who an op acts on. */
public sealed interface TargetRef {
    /** The entity of the current contact (hit/block handlers). */
    public data object Victim : TargetRef
    public data object Caster : TargetRef
    /** Who dealt the damage of a `hurt` event (`event.attacker`, ADR-0303 10.3). */
    public data object Attacker : TargetRef
    /** Non-allies within [radius] of [around]; [maxCount] keeps only the nearest N to [around] (`max_count`). */
    public data class Enemies(val around: PointRef, val radius: Value<Double>, val maxCount: Int? = null) : TargetRef
    /** Team mates (never the caster) within [radius] of [around]; the caster is listed first, unconditionally, only when [includeSelf] (`include_self`, default false). */
    public data class Allies(val around: PointRef, val radius: Value<Double>, val maxCount: Int? = null, val includeSelf: Boolean = false) : TargetRef
}

/** A place: a frame point or a computed vector (world space). */
public sealed interface LocationSpec {
    public data class AtPoint(val point: PointRef) : LocationSpec
    public data class AtValue(val value: Value<com.astarworks.astera.core.domain.geometry.Vec3>) : LocationSpec
}

/** Scalar skill parameter (`params:`). */
public sealed interface ParamValue {
    public data class Num(val value: Double) : ParamValue
    public data class Text(val value: String) : ParamValue
    public data class Flag(val value: Boolean) : ParamValue
}
