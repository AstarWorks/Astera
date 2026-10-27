package com.astarworks.astera.core.domain.skillengine.expr

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.Vec3 as GeoVec3

/**
 * Expression contract (ADR-0299). Minimal declarations created by the contract worker so the
 * skill model compiles; the CEL worker owns this package and may extend (not rename) these types.
 */
public interface ExprCompiler {
    /** Compile once at load; [env] declares the variables (name -> [ExprType]) the source may use. */
    public fun compile(source: String, env: ExprEnvSpec): Result<CompiledExpr, ExprError>
}

public interface CompiledExpr {
    /** Evaluate per tick against concrete variable values. */
    public fun eval(env: ExprEnv): ExprValue
}

public enum class ExprType { NUM, VEC3, QUAT, BOOL, STR, REF }

public sealed interface ExprValue {
    public data class Num(val value: Double) : ExprValue
    public data class Vec3(val value: GeoVec3) : ExprValue
    public data class Quat(val value: Quaternion) : ExprValue
    public data class Bool(val value: Boolean) : ExprValue
    public data class Str(val value: String) : ExprValue

    /** Reference to a frame or frame point by name (e.g. "blade_a.tip"). */
    public data class Ref(val name: String) : ExprValue
}

/** Compile-time variable declarations. */
public data class ExprEnvSpec(val variables: Map<String, ExprType>)

/** Run-time variable values. */
public interface ExprEnv {
    public fun get(name: String): ExprValue?
}

public sealed interface ExprError {
    public val message: String

    public data class Syntax(val source: String, override val message: String) : ExprError
    public data class Type(val source: String, override val message: String) : ExprError
    public data class UnknownName(val source: String, val name: String) : ExprError {
        override val message: String get() = "unknown name '$name' in '$source'"
    }
}
