package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.expr.cel.ExprClassifier
import com.astarworks.astera.core.application.skillengine.load.SkillCatalogView
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.runtime.player.SeededStream
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath

/** Everything an op's lower() may read. Built by the compiler per state and re-derived per occurrence by [child]. */
public class LowerContext(
    public val skillId: String,
    public val state: StatePath,
    /** Group / part path so far. */
    public val instancePrefix: InstanceId,
    /** i, n at this level, parent chain (2.0a). */
    public val occurrence: Occurrence,
    /** Occurrence tick relative to the state entry. */
    public val clockOffset: Int,
    /** Resolves `anchor: ball` to this occurrence's InstanceId + basis class (S/T/L). */
    public val anchors: AnchorScope,
    /** content/frames. */
    public val frames: FrameTables,
    public val parts: PartCatalog,
    public val skills: SkillCatalogView,
    public val conditions: Map<String, CompiledExpr>,
    public val exprs: ExprCompiler,
    public val config: EngineConfig,
    /** rand() for sample evaluation (C4d). */
    public val seed: SeededStream,
    /** YAML path of the op being lowered; [child] appends `.do[<doIndex>]`. */
    public val src: SrcPath,
) {
    public fun child(op: OpId, i: Int?, n: Int, tick: Int, doIndex: Int = 0): LowerContext = LowerContext(
        skillId, state,
        InstanceId(instancePrefix.path + InstanceId.Segment(op, i)),
        Occurrence(i ?: 0, n, occurrence),
        tick, anchors, frames, parts, skills, conditions, exprs, config, seed,
        SrcPath(src.value + ".do[" + doIndex + "]"),
    )

    /** S / T / L (3.5), from the expression source and this scope's anchor bases. */
    public fun classify(expr: CompiledExpr): ExprClass = ExprClassifier.analyse(expr.source) { anchors.resolve(it)?.basis }.cls

    /** Check 9: a timing field must be a compile-time integer. Any L value, `rand()`, `t` or runtime position is [SkillDecodeError.RuntimeTiming]. */
    public fun requireStaticTiming(path: String, field: String, v: Value<Double>): Result<Int, SkillDecodeError> =
        ExprClassifier.staticTicks(this, path, field, v)
}

public data class Occurrence(val i: Int, val n: Int, val parent: Occurrence?)

public enum class ExprClass { S, T, L }
