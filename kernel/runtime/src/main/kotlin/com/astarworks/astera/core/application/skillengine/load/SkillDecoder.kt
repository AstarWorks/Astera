package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprError
import com.astarworks.astera.core.domain.skillengine.model.SkillDef

public sealed interface SkillDecodeError {
    public val skillId: String
    public val path: String
    public val message: String

    public data class MissingField(override val skillId: String, override val path: String, val name: String) : SkillDecodeError {
        override val message: String get() = "missing '$name'"
    }

    public data class UnknownKey(override val skillId: String, override val path: String, val key: String) : SkillDecodeError {
        override val message: String get() = "unknown key '$key'"
    }

    public data class TypeMismatch(override val skillId: String, override val path: String, val expected: String, val actual: String) : SkillDecodeError {
        override val message: String get() = "expected $expected but found $actual"
    }

    public data class UnknownOp(override val skillId: String, override val path: String, val op: String, val section: String) : SkillDecodeError {
        override val message: String get() = "op '$op' is not allowed in section '$section'"
    }

    public data class DuplicateId(override val skillId: String, override val path: String, val id: String) : SkillDecodeError {
        override val message: String get() = "duplicate id '$id'"
    }

    public data class UndefinedReference(override val skillId: String, override val path: String, val ref: String) : SkillDecodeError {
        override val message: String get() = "undefined frame/point/param '$ref'"
    }

    /** Any other rule violation (`fail(message)` from an op decoder, skin patch touching gameplay, bad `at:`, ...). */
    public data class Invalid(override val skillId: String, override val path: String, override val message: String) : SkillDecodeError

    public data class BadExpr(override val skillId: String, override val path: String, val error: ExprError) : SkillDecodeError {
        override val message: String get() = error.message
    }
}

/** Generic composed tree -> typed model. Pure and synchronous; no expression compilation here. */
public interface SkillDecoder {
    public fun decode(composed: ComposedSkill): Result<SkillDef, SkillDecodeError>
}

/** A decoded skill plus every expression compiled once (keyed by model path, e.g. `gameplay.on.release[fly].velocity`). */
public data class CompiledSkill(
    val def: SkillDef,
    val exprs: Map<String, CompiledExpr>,
)

/** Compiles every `Value.Expr` of [SkillDef] and checks frame/point/param references. */
public interface SkillCompiler {
    public fun compile(def: SkillDef): Result<CompiledSkill, SkillDecodeError>
}
