package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/** One gateway call as the in-memory recorder saw it (W1-20 owns the real recorder; A2 diffs it against ExpansionDump.json). */
data class RecordedCall(val tick: Int, val verb: String, val instance: String, val payload: String)

/** Harness base for v3 skill-engine scenarios: real loaders, real SkillCompiler, in-memory gateways. Stub: W1-20 implements it. */
abstract class SkillEngineScenario {
    /** docs/adr/0303-examples/<name>.yaml (multi-document: skill + sub-skills/parts). */
    fun loadExample(name: String): CompiledSkill = TODO("W1-20: loadExample")

    fun loadInline(yaml: String): Result<CompiledSkill, SkillDecodeError> = TODO("W1-20: loadInline")

    fun play(skill: CompiledSkill, seed: Long, events: List<RuntimeEvent> = emptyList(), ticks: Int? = null): List<RecordedCall> =
        TODO("W1-20: play")

    /** From the table: static rows plus pasted sub-tables. */
    fun expectedCalls(skill: CompiledSkill, events: List<RuntimeEvent>): List<RecordedCall> = TODO("W1-20: expectedCalls")

    /** The `# expand:` block of the example file. */
    fun dumpOf(name: String): String = TODO("W1-20: dumpOf")
}
