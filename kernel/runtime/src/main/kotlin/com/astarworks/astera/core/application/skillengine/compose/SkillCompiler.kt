package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillCatalogView
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/**
 * SkillDef -> ExpansionTable (ADR-0303 C1). Replaces the v2 `load.DefaultSkillCompiler`. Pipeline, each step a class in this package
 * owned by its wave-1 package:
 *   PartInliner.inline(def, parts, skills)          W1-14 / W1-15
 *   MachineLowerer + MachineTables.lower            W1-16
 *   GroupUnroller.unroll(state, ops, lc)            W1-01
 *   per-op OpSpec.lower                             W1-03 .. W1-13
 *   LawSegmenter.segments(instance, laws, lc)       W1-08
 *   EventScoper.scope(...)                          W1-16
 *   StaticChecks.run(table, config)                 W1-17
 *   TableAssembler.assemble(...)                    W0-01 (done)
 */
public class SkillCompiler(
    private val specs: OpSpecRegistry,
    private val exprs: ExprCompiler,
    private val frames: FrameTables,
    private val config: EngineConfig,
) {
    public fun compile(def: SkillDef, parts: PartCatalog, skills: SkillCatalogView, sampleSeed: Long = 0L): Result<CompiledSkill, SkillDecodeError> =
        TODO("W1-16: SkillCompiler pipeline wiring (PartInliner, MachineTables, GroupUnroller, op lowering, StaticChecks)")
}
