package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.op.OpSection

/** A node of the generic YAML tree (snakeyaml output: maps, lists, scalars). */
public typealias YamlNode = Any?

/** What a document decode needs besides the file: the frame/part/skill catalogs it may name. Stub: W1-18 fills it. */
public class GrammarEnv(public val knownParts: Set<String> = emptySet(), public val knownSkills: Set<String> = emptySet())

/** Replaces `YamlSkillDecoder`. Stub: W1-18 implements it (top-level fields, strict unknown keys, TimingSpec per spec.timing). */
public class SkillDocumentDecoder(private val specs: OpSpecRegistry, private val exprs: ExprCompiler) {
    public fun decode(file: String, root: YamlNode, env: GrammarEnv): Result<SkillDef, SkillDecodeError> =
        TODO("W1-18: SkillDocumentDecoder.decode")
}

/** Decodes an op list of one section; a map with `do:` and no `op:` is GroupOpSpec, `use:` is UseOpSpec. Stub: W1-18. */
public class OpListDecoder(private val specs: OpSpecRegistry) {
    public fun decode(list: YamlNode, section: OpSection, ctx: GrammarContext, path: String): Result<List<com.astarworks.astera.core.application.skillengine.load.spec.OpNode>, SkillDecodeError> =
        TODO("W1-18: OpListDecoder.decode")
}
