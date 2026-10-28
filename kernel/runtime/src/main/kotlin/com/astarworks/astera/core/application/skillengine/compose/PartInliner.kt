package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.part.PartExpander
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.part.PartError

/**
 * ADR-0303 section 9, state-level part calls, run before lowering: validates every `use: <part>` of [SkillDef.machine]
 * (and, transitively, of the parts it calls) and returns the skill with all part calls replaced by the part's states
 * (ids prefixed with the call id, op ids with their state path, emits as `<call>.<event>`, finals raising `<call>.end`).
 * Reuses [PartExpander] for the rewrite itself.
 *
 * Checks, each a load error with the call's path: unknown part; call cycle (`a -> b -> a`); nesting deeper than
 * `max_part_depth`; `with:` args typed and required; `frames:` complete and bound to a frame the caller can see
 * (its anchors, frames, `frames_in`, state/op ids, or `caster`/`world`/`contact`/`target`); a caller transition on
 * `<call>.<event>` whose event the part does not emit.
 * `use: skill:<id>` calls are not touched (W1-15).
 */
public object PartInliner {
    private val BUILTIN_FRAMES = setOf("caster", "world", "contact", "target", "victim", "attacker", "aim", "cast")

    public fun inline(def: SkillDef, parts: PartCatalog, config: EngineConfig = EngineConfig()): Result<SkillDef, SkillDecodeError> {
        if (def.machine.states.isEmpty()) return Result.success(def)
        val resolved = LinkedHashMap<String, Part>()
        val scope = HashSet(BUILTIN_FRAMES).apply {
            def.anchors.forEach { add(it.id) }
            def.frames.forEach { add(it.id) }
            addAll(def.framesIn.keys)
        }
        validate(def.id, def.machine, "machine", scope, parts, config, emptyList(), resolved)?.let { return Result.failure(it) }
        return when (val r = PartExpander(resolved, config.maxPartDepth).expand(def.machine)) {
            is Result.Success -> Result.success(def.copy(machine = r.value))
            is Result.Failure -> Result.failure(r.error.toDecodeError(def.id))
        }
    }

    private fun validate(
        skillId: String,
        m: Machine,
        path: String,
        scope: Set<String>,
        parts: PartCatalog,
        config: EngineConfig,
        stack: List<String>,
        resolved: MutableMap<String, Part>,
    ): SkillDecodeError? {
        val visible = scope + m.states.keys
        for (s in m.states.values) {
            val at = "$path.states.${s.id}"
            val call = s.use
            if (call != null) {
                if (call.part.startsWith("skill:")) continue
                val part = parts.part(call.part) ?: return SkillDecodeError.UndefinedReference(skillId, "$at.use", call.part)
                if (part.id in stack) return SkillDecodeError.Invalid(skillId, "$at.use", "part call cycle: ${(stack.dropWhile { it != part.id } + part.id).joinToString(" -> ")}")
                if (stack.size + 1 > config.maxPartDepth) return SkillDecodeError.Invalid(skillId, "$at.use", "part nesting deeper than ${config.maxPartDepth}")
                checkCall(skillId, at, part, call.args, call.frames, visible)?.let { return it }
                resolved[part.id] = part
                validate(skillId, part.machine, "part:${part.id}", part.framesIn.keys + BUILTIN_FRAMES, parts, config, stack + part.id, resolved)?.let { return it }
                checkEmitsUsed(skillId, m, s, part)?.let { return it }
            }
        }
        return null
    }

    internal fun checkCall(
        skillId: String,
        at: String,
        part: Part,
        args: Map<String, Any?>,
        frames: Map<String, String>,
        visible: Set<String>,
    ): SkillDecodeError? {
        for ((k, v) in args) {
            val spec = part.params[k] ?: return SkillDecodeError.UnknownKey(skillId, "$at.with.$k", k)
            if (!typeOk(spec.type.key, v)) return SkillDecodeError.TypeMismatch(skillId, "$at.with.$k", spec.type.key, v?.toString() ?: "null")
        }
        for ((name, spec) in part.params) if (spec.required && name !in args) return SkillDecodeError.MissingField(skillId, "$at.with", name)
        for (k in frames.keys) if (k !in part.framesIn) return SkillDecodeError.UnknownKey(skillId, "$at.frames.$k", k)
        for (k in part.framesIn.keys) if (k !in frames) return SkillDecodeError.MissingField(skillId, "$at.frames", k)
        for ((k, v) in frames) {
            val root = v.substringBefore('.').substringBefore('[')
            if (root !in visible) return SkillDecodeError.UndefinedReference(skillId, "$at.frames.$k", v)
        }
        return null
    }

    /** CEL strings (`= ...`) are judged in the caller; otherwise the YAML scalar must match the param type. */
    private fun typeOk(type: String, v: Any?): Boolean = when {
        v is String && v.startsWith("= ") -> true
        type == "bool" -> v is Boolean
        type == "string" -> v is String
        else -> v is Number
    }

    private fun checkEmitsUsed(skillId: String, m: Machine, call: MachineState, part: Part): SkillDecodeError? {
        val prefix = call.id + "."
        val known = part.emits.toSet() + "end"
        for (s in m.states.values) for ((i, t) in s.on.withIndex()) {
            val ev = t.event ?: continue
            if (!ev.startsWith(prefix)) continue
            val tail = ev.removePrefix(prefix)
            if (tail !in known && !tail.contains('.')) {
                return SkillDecodeError.UndefinedReference(skillId, "machine.states.${s.id}.on[$i].event", "$ev (part '${part.id}' emits ${known.joinToString()})")
            }
        }
        return null
    }

    private fun PartError.toDecodeError(skillId: String): SkillDecodeError = when (this) {
        is PartError.UnknownPart -> SkillDecodeError.UndefinedReference(skillId, path, part)
        else -> SkillDecodeError.Invalid(skillId, path, message)
    }
}
