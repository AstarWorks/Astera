package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.part.PartExpander
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.part.PartCall
import com.astarworks.astera.core.domain.skillengine.part.PartError

/**
 * ADR-0303 section 9, state-level part calls, run before lowering: validates every `use: <part>` of [SkillDef.machine]
 * (and, transitively, of the parts it calls) and returns the skill with all part calls replaced by the part's states
 * (ids prefixed with the call id, op ids with their state path, emits as `<call>.<event>`, finals raising `<call>.end`).
 * Reuses [PartExpander] for the rewrite itself.
 *
 * Every call is bound first ([bind]): the `${p}` of the part's raw op maps are replaced by the call's arguments (or the param
 * defaults) before anything is lowered, so a bound part is a plain machine with no params left.
 *
 * Checks, each a load error with the call's path: unknown part; call cycle (`a -> b -> a`); nesting deeper than
 * `max_part_depth`; `with:` args typed and required; `frames:` complete and bound to a frame the caller can see
 * (its anchors, frames, `frames_in`, state/op ids, or `caster`/`world`/`contact`/`target`); a caller transition on
 * `<call>.<event>` whose event the part does not emit.
 * `use: skill:<id>` calls are not touched here ([SkillCallInliner] renames them to synthetic parts first).
 */
public object PartInliner {
    private val BUILTIN_FRAMES = setOf("caster", "world", "contact", "target", "victim", "attacker", "aim", "cast")

    /** The inlined skill plus the deepest nesting of state-level part calls it contains (feeds `Bounds.partDepth`). */
    public class Inlined(public val def: SkillDef, public val depth: Int)

    public fun inline(def: SkillDef, parts: PartCatalog, config: EngineConfig = EngineConfig()): Result<SkillDef, SkillDecodeError> =
        when (val r = inlineCounted(def, parts, config)) {
            is Result.Success -> Result.success(r.value.def)
            is Result.Failure -> Result.failure(r.error)
        }

    public fun inlineCounted(def: SkillDef, parts: PartCatalog, config: EngineConfig = EngineConfig()): Result<Inlined, SkillDecodeError> {
        // the skill's own params take their declared default (a call site hands a skill its arguments through SkillCallInliner)
        val rootEnv = LinkedHashMap<String, Any?>()
        for ((name, spec) in def.params) if (spec.default != null) rootEnv[name] = spec.default
        val rootMachine = ParamSubst(rootEnv, strict = false).machine(def.machine)
        if (rootMachine.states.isEmpty()) return Result.success(Inlined(def.copy(machine = rootMachine), 0))
        val resolved = LinkedHashMap<String, Part>()
        val scope = HashSet(BUILTIN_FRAMES).apply {
            def.anchors.forEach { add(it.id) }
            addAll(def.framesIn.keys)
            // a frame or projectile body of the machine-level `world:` list lives for the whole cast, so every state may hand it to a part
            for (op in def.machine.world) if (op["op"] == "frame" || op["op"] == "projectile") (op["id"] as? String)?.let { add(it) }
        }
        val w = Walk(def.id, parts, config, resolved)
        val bound = when (val r = walk(w, rootMachine, "machine", scope, emptyList())) {
            is Result.Success -> r.value
            is Result.Failure -> return Result.failure(r.error)
        }
        return when (val r = PartExpander(resolved, config.maxPartDepth).expand(bound)) {
            is Result.Success -> Result.success(Inlined(def.copy(machine = r.value), w.depth))
            is Result.Failure -> Result.failure(r.error.toDecodeError(def.id))
        }
    }

    private class Walk(val skillId: String, val parts: PartCatalog, val config: EngineConfig, val resolved: MutableMap<String, Part>) {
        var depth: Int = 0
    }

    /**
     * Binds [part] to one call: every `${p}` of its machine becomes the call's argument (or the param's default), so the lowering
     * never sees a [com.astarworks.astera.core.domain.skillengine.model.Value.ParamRef]. A CEL argument (`= e`) is pasted as `(e)`
     * inside another CEL string and stays itself when it is the whole value; an unknown `${x}` is an [SkillDecodeError.UndefinedReference].
     */
    public fun bind(skillId: String, at: String, part: Part, args: Map<String, Any?>): Result<Part, SkillDecodeError> {
        val env = LinkedHashMap<String, Any?>()
        for ((name, spec) in part.params) {
            val v = if (name in args) args[name] else spec.default
            if (v != null) env[name] = v
        }
        val subst = ParamSubst(env, strict = true)
        val machine = subst.machine(part.machine)
        subst.missing?.let {
            return Result.failure(SkillDecodeError.UndefinedReference(skillId, "$at.with", "\${$it} (part '${part.id}' has no such param)"))
        }
        return Result.success(part.copy(params = emptyMap(), machine = machine))
    }

    private fun walk(w: Walk, m: Machine, path: String, scope: Set<String>, stack: List<String>): Result<Machine, SkillDecodeError> {
        val visible = scope + m.states.keys
        val states = LinkedHashMap<String, MachineState>()
        for ((key, s) in m.states) {
            val at = "$path.states.${s.id}"
            val call = s.use
            if (call == null || call.part.startsWith("skill:")) {
                states[key] = s
                continue
            }
            val part = w.parts.part(call.part) ?: return Result.failure(SkillDecodeError.UndefinedReference(w.skillId, "$at.use", call.part))
            if (part.id in stack) {
                return Result.failure(SkillDecodeError.Invalid(w.skillId, "$at.use", "part call cycle: ${(stack.dropWhile { it != part.id } + part.id).joinToString(" -> ")}"))
            }
            if (stack.size + 1 > w.config.maxPartDepth) {
                return Result.failure(SkillDecodeError.Invalid(w.skillId, "$at.use", "part nesting deeper than ${w.config.maxPartDepth}"))
            }
            checkCall(w.skillId, at, part, call.args, call.frames, visible)?.let { return Result.failure(it) }
            w.depth = maxOf(w.depth, stack.size + 1)
            val bound = when (val r = bind(w.skillId, at, part, call.args)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            val inner = when (val r = walk(w, bound.machine, "part:${part.id}", part.framesIn.keys + BUILTIN_FRAMES, stack + part.id)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            checkEmitsUsed(w.skillId, m, s, part)?.let { return Result.failure(it) }
            val synthetic = "${part.id}@$at"
            w.resolved[synthetic] = bound.copy(id = synthetic, machine = inner)
            states[key] = s.copy(use = PartCall(synthetic, emptyMap(), call.frames))
        }
        return Result.success(m.copy(states = states))
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

/** Deep `${p}` substitution over raw op maps (see [PartInliner.bind]). [strict] records an unknown name in [missing]; lenient leaves it. */
internal class ParamSubst(private val env: Map<String, Any?>, private val strict: Boolean) {
    var missing: String? = null

    fun machine(m: Machine): Machine = m.copy(states = m.states.mapValues { (_, s) -> state(s) }, world = ops(m.world))

    private fun state(s: MachineState): MachineState = s.copy(
        forTicks = any(s.forTicks),
        enter = ops(s.enter),
        doOps = ops(s.doOps),
        exit = ops(s.exit),
        on = s.on.map { t -> t.copy(afterTicks = any(t.afterTicks), whenExpr = any(t.whenExpr) as String?, effects = ops(t.effects)) },
        use = s.use?.let { c -> c.copy(args = c.args.mapValues { (_, v) -> any(v) }) },
        parallel = s.parallel.map { machine(it) },
    )

    @Suppress("UNCHECKED_CAST")
    fun ops(l: List<Map<String, Any?>>): List<Map<String, Any?>> = l.map { any(it) as Map<String, Any?> }

    fun any(v: Any?): Any? = when (v) {
        is String -> str(v)
        is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { out -> for ((k, x) in v) out[k] = any(x) }
        is List<*> -> v.map { any(it) }
        else -> v
    }

    private fun str(s: String): Any? {
        if (s.startsWith("= ") && env.isNotEmpty()) {
            // `= p` / `= ${p}` naming exactly one numeric param IS that number (compile-time fields such as a state's `for` need the value, not a CEL cell)
            val body = s.substring(2).trim()
            val name = EXACT.matchEntire(body)?.groupValues?.get(1) ?: body
            (env[name] as? Number)?.let { return it }
            return bare(s)
        }
        if (!s.contains("\${")) return s
        EXACT.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            if (n in env) return env[n]
            unknown(n)
            return s
        }
        val cel = s.startsWith("= ")
        return REF.replace(s) { m ->
            val n = m.groupValues[1]
            if (n !in env) {
                unknown(n)
                m.value
            } else render(env[n], cel) ?: m.value
        }
    }

    /** `${p}` is "the same as the bare identifier `p`" (ExprCompiler doc): a bare param name in a CEL source gets the same substitution. */
    private fun bare(s: String): String {
        val withRefs = if (s.contains("\${")) {
            REF.replace(s) { m ->
                val n = m.groupValues[1]
                if (n !in env) {
                    unknown(n)
                    m.value
                } else render(env[n], true) ?: m.value
            }
        } else s
        return IDENT.replace(withRefs) { m ->
            val n = m.groupValues[2]
            val q = m.groupValues[1]
            if (q.isNotEmpty() || n !in env) m.value else render(env[n], true) ?: m.value
        }
    }

    private fun unknown(n: String) {
        if (strict && missing == null) missing = n
    }

    private fun render(v: Any?, cel: Boolean): String? = when {
        v is String && v.startsWith("= ") -> if (cel) "(" + v.removePrefix("= ") + ")" else null
        v is String -> if (cel) "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else v
        v is Number -> {
            val d = v.toDouble()
            // inside CEL a whole number stays a double literal (`3.0`): `3 / 4` would be integer division and give 0 (iron-blade-lunge dash speed)
            if (d == Math.floor(d) && Math.abs(d) < 1e15) (if (cel) d.toString() else d.toLong().toString()) else d.toString()
        }
        else -> v?.toString()
    }

    private companion object {
        val EXACT = Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""")
        /** A quoted string literal (group 1, kept as is) or a bare identifier not preceded by `.`, `$` or a word char and not a call (group 2). */
        val IDENT = Regex("""("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(?<![\w.$])([A-Za-z_][A-Za-z0-9_]*)(?![\w(])""")
        val REF = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")
    }
}
