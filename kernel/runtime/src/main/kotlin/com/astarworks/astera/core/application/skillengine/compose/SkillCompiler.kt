package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillCatalogView
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.TickRange

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
    public fun compile(def: SkillDef, parts: PartCatalog, skills: SkillCatalogView, sampleSeed: Long = 0L): Result<CompiledSkill, SkillDecodeError> {
        val lowerer = SectionLowerer { state, section, ops ->
            if (ops.isEmpty()) Result.Success(Lowered(emptyList()))
            else Result.Failure(
                SkillDecodeError.Invalid(def.id, "machine.states.${state.value}.$section", "op decoding of generic op maps awaits W1-18 (OpListDecoder takes YamlNode)"),
            )
        }
        return compileWith(def, lowerer, sampleSeed)
    }

    /** Machine + event scoping + assembly. Part inlining, group unrolling and static checks join here as their packages land. */
    internal fun compileWith(def: SkillDef, lowerer: SectionLowerer, sampleSeed: Long): Result<CompiledSkill, SkillDecodeError> {
        val m = when (val r = MachineTables.lower(def.id, def.machine, lowerer, config)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val subs = when (val r = EventScoper.scope(def.id, m.states, m.subTables, emptyList(), config)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val bounds = Bounds(
            instances = emptyMap(), liveDisplaysPeak = 0, liveDisplaysPeakTick = 0, groupDepth = 0, partDepth = 0,
            path = TickRange(0, 0), sameTickDepth = 0,
        )
        val table = TableAssembler.assemble(def.id, sampleSeed, emptyMap(), bounds, m.states, subs, m.writes, def.conditions)
        return Result.Success(CompiledSkill(def, table))
    }
}
