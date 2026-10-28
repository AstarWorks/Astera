package com.astarworks.astera.core.application.skillengine.load.spec

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId

/**
 * A decoded v3 op. The v2 `SkillOp` is sealed inside the domain module and carries a plain-string id, so v3 ops (one per
 * `<Name>OpSpec.kt`, owned by its wave-1 package) extend this open interface instead.
 */
public interface OpNode {
    public val id: OpId
    public val whenExpr: WhenRef?
    public val timing: Timing?
}

public sealed interface WhenRef {
    public data class Named(val cond: String) : WhenRef
    public data class Expr(val compiled: CompiledExpr) : WhenRef
}

/** One op: its name, its field table (schema + decode), and how it lowers into table rows. One Kotlin file per implementation. */
public interface OpSpec<O : OpNode> {
    /** yaml `op:` value (or "group" for `do:` without `op`). */
    public val name: String
    public val section: OpSection

    /** EXTENDED / POINT, or null (not timed). */
    public val timing: TimingKind?

    /** Own fields only; TimingSpec.fields are merged by the registry. */
    public val fields: Fields

    public fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<O, SkillDecodeError>

    public fun lower(op: O, lc: LowerContext): Result<Lowered, SkillDecodeError>
}
