package com.astarworks.astera.core.domain.skillengine.expr

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3 as GeoVec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/**
 * Expression layer of the skill engine (ADR-0301). A skill field written as `= <expr>` is compiled ONCE at load
 * with [ExprCompiler.compile] and evaluated every tick with [CompiledExpr.eval]. The implementation is CEL
 * (kernel/runtime, `skillengine/expr/cel`); callers only see these interfaces.
 *
 * Authoring notes: `${param}` inside a source is the same as the bare identifier `param`. Numeric literals that are
 * whole numbers are accepted next to doubles (`2 * t` works). Frame names are declared in [ExprEnvSpec.frames]
 * and usable as bare identifiers (`pos(volley)`).
 */
public interface ExprCompiler {
    /** Parses and type-checks [source] against [env]. A leading `=` is allowed and ignored. */
    public fun compile(source: String, env: ExprEnvSpec): Result<CompiledExpr, ExprError>
}

public interface CompiledExpr {
    /** The source text this was compiled from (for logs). */
    public val source: String

    /** Evaluates against [env]. Throws [ExprEvalException] on a runtime failure (division of vectors, missing frame, ...). */
    public fun eval(env: ExprEnv): ExprValue
}

public sealed interface ExprValue {
    public data class Num(val value: Double) : ExprValue
    public data class Vec3(val value: GeoVec3) : ExprValue
    public data class Quat(val value: Quaternion) : ExprValue
    public data class Bool(val value: Boolean) : ExprValue
    public data class Str(val value: String) : ExprValue

    /** A frame (`point == null`) or a named point on a frame. Resolved through [FrameLookup]. */
    public data class Ref(val name: String, val point: String? = null) : ExprValue
}

public enum class ExprType { NUM, VEC3, QUAT, BOOL, STR, REF }

/** Declared identifiers for a compile: variable name -> type, plus the frame names usable as bare identifiers. */
public data class ExprEnvSpec(
    val variables: Map<String, ExprType> = emptyMap(),
    val frames: Set<String> = emptySet(),
)

/** World-space queries against the live frame tree. */
public interface FrameLookup {
    /** World pose of frame [frame], or of its model point [point]; null when unknown. */
    public fun worldPose(frame: String, point: String?): Frame?

    /** World velocity (blocks per tick) of a frame or point; ZERO when unknown. */
    public fun velocity(frame: String, point: String?): GeoVec3
}

/**
 * Cast-level answers the CEL functions `aim_point` and `blade_len` need and a frame tree cannot give: the world
 * (blocks, entities) along the caster's look ray, and the item point table.
 */
public interface ExprHost {
    /** Point on the look ray of [caster] at [range] blocks, clamped to the first block or entity the ray meets. */
    public fun aimPoint(caster: ExprValue.Ref, range: Double): GeoVec3

    /** Grip-to-tip (or root-to-tip) distance of the item point table of [frame]. */
    public fun bladeLen(frame: ExprValue.Ref): Double
}

/** Values at eval time. [seed] makes `rand(stream)` deterministic per cast; [host] serves `aim_point` / `blade_len`. */
public class ExprEnv(
    public val variables: Map<String, ExprValue>,
    public val frames: FrameLookup,
    public val seed: Long = 0L,
    public val host: ExprHost? = null,
)

public sealed interface ExprError {
    public val message: String

    /** 1-based column of the problem in the source, 0 when unknown. */
    public val column: Int

    public data class Syntax(override val message: String, override val column: Int) : ExprError
    public data class Type(override val message: String, override val column: Int) : ExprError
    public data class Unsupported(override val message: String, override val column: Int) : ExprError
}

public class ExprEvalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
