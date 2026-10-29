package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.MachineTransition
import com.astarworks.astera.core.domain.skillengine.part.OpMap
import com.astarworks.astera.core.domain.skillengine.part.ParamSpec
import com.astarworks.astera.core.domain.skillengine.part.ParamType
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.part.PartCall
import com.astarworks.astera.core.domain.skillengine.part.PartError
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.nio.file.Files
import java.nio.file.Path

/** Internal control flow of the tree passes; the public API turns it into a [PartError] inside a [Result]. */
internal class PartAbort(val error: PartError) : Exception(error.toString())

internal fun abort(path: String, message: String): Nothing = throw PartAbort(PartError.Invalid(path, message))

/**
 * Strict parser for ADR-0303 `kind: Part` documents and for the `machine:` block of a skill (sections 9-10).
 * Unknown keys raise, params are typed and required (or carry an explicit identity default), references are checked.
 * The YAML loader reads `on:` as the plain string key (YAML 1.2 style), exactly like the skill composer.
 */
public object PartParser {
    public const val API_VERSION: String = "astera/v3"

    private class StrictResolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Regex("^(?:true|false)$").toPattern(), "tf")
            addImplicitResolver(Tag.INT, INT, "-+0123456789")
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.")
            addImplicitResolver(Tag.MERGE, MERGE, "<")
            addImplicitResolver(Tag.NULL, NULL, "~nN\u0000")
            addImplicitResolver(Tag.NULL, EMPTY, null)
        }
    }

    private fun newYaml(): Yaml {
        val opts = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            setAllowRecursiveKeys(false)
            maxAliasesForCollections = 200
        }
        val dumper = DumperOptions()
        return Yaml(SafeConstructor(opts), Representer(dumper), dumper, opts, StrictResolver())
    }

    /** Every YAML document of [text] as a string-keyed tree (maps are insertion-ordered). */
    @Suppress("UNCHECKED_CAST")
    public fun documents(file: String, text: String): Result<List<Map<String, Any?>>, PartError> {
        val raw = try {
            newYaml().loadAll(text).toList()
        } catch (e: YAMLException) {
            return Result.failure(PartError.Invalid(file, e.message?.lineSequence()?.firstOrNull() ?: "invalid YAML"))
        }
        val out = ArrayList<Map<String, Any?>>()
        for ((i, doc) in raw.withIndex()) {
            if (doc == null) continue
            val m = doc as? Map<*, *> ?: return Result.failure(PartError.Invalid("$file#${i + 1}", "document root must be a mapping"))
            out += normalize(m) as Map<String, Any?>
        }
        return Result.success(out)
    }

    private fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> v.forEach { (k, x) -> out[k.toString()] = normalize(x) } }
        is Collection<*> -> v.map { normalize(it) }
        else -> v
    }

    /** Parses every `kind: Part` document in [text]; `kind: Skill` and `kind: Patch` documents are skipped, anything else is an error. */
    public fun parseParts(file: String, text: String): Result<List<Part>, List<PartError>> {
        val docs = documents(file, text).let { if (it is Result.Failure) return Result.failure(listOf(it.error)) else (it as Result.Success).value }
        val parts = ArrayList<Part>()
        val errors = ArrayList<PartError>()
        for ((i, doc) in docs.withIndex()) {
            val where = "$file#${i + 1}"
            when (doc["kind"]) {
                "Part" -> try {
                    parts += part(doc, where)
                } catch (e: PartAbort) {
                    errors += e.error
                }
                "Skill", "Patch" -> Unit // a Patch document is applied by the composer (R35), it is not a part
                else -> errors += PartError.Invalid("$where.kind", "kind must be Part, Skill or Patch, was '${doc["kind"]}'")
            }
        }
        return if (errors.isEmpty()) Result.success(parts) else Result.failure(errors)
    }

    /** Loads every `*.yaml` under [dir] (the `content/skill-parts` layout); duplicate part ids are an error. */
    public fun loadDirectory(dir: Path): Result<Map<String, Part>, List<PartError>> {
        if (!Files.isDirectory(dir)) return Result.success(emptyMap())
        val files = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".yaml") }.sorted().toList() }
        val out = LinkedHashMap<String, Part>()
        val errors = ArrayList<PartError>()
        for (f in files) {
            val rel = dir.relativize(f).toString()
            when (val r = parseParts(rel, Files.readString(f))) {
                is Result.Success -> r.value.forEach { p ->
                    if (out.put(p.id, p) != null) errors += PartError.Invalid(rel, "part '${p.id}' is defined twice")
                }
                is Result.Failure -> errors += r.error
            }
        }
        return if (errors.isEmpty()) Result.success(out) else Result.failure(errors)
    }

    /** The `machine:` block of a skill tree, or null when the skill has none. */
    public fun skillMachine(tree: Map<String, Any?>): Result<Machine?, PartError> = try {
        Result.success(tree["machine"]?.let { machine(it, "machine") })
    } catch (e: PartAbort) {
        Result.failure(e.error)
    }

    // ---- parsing ----------------------------------------------------------------------------------------------

    public fun part(doc: Map<String, Any?>, where: String): Part {
        checkKeys(doc, where, setOf("apiVersion", "kind", "id", "params", "frames_in", "frames_out", "emits", "machine"))
        if (doc["apiVersion"] != API_VERSION) abort("$where.apiVersion", "apiVersion must be $API_VERSION, was '${doc["apiVersion"]}'")
        val id = doc["id"] as? String ?: abort("$where.id", "missing string 'id'")
        if (!Regex("[a-z][a-z0-9_-]*").matches(id)) abort("$where.id", "part id '$id' must match [a-z][a-z0-9_-]*")
        val params = LinkedHashMap<String, ParamSpec>()
        for ((name, raw) in doc["params"].asMapOrEmpty("$where.params")) {
            params[name] = param(name, raw, "$where.params.$name")
        }
        val framesIn = stringMap(doc["frames_in"], "$where.frames_in")
        val framesOut = stringMap(doc["frames_out"], "$where.frames_out")
        val emits = (doc["emits"] as? List<*>)?.map { it as? String ?: abort("$where.emits", "emits entries must be strings") }
            ?: if (doc["emits"] == null) emptyList() else abort("$where.emits", "emits must be a list of event names")
        val mach = machine(doc["machine"] ?: abort("$where.machine", "a part is an automaton: missing 'machine'"), "$where.machine")
        return Part(id, params, framesIn, framesOut, emits, mach)
    }

    private fun param(name: String, raw: Any?, where: String): ParamSpec {
        val m = raw as? Map<*, *> ?: abort(where, "expected {type, required|default}")
        @Suppress("UNCHECKED_CAST")
        checkKeys(m as Map<String, Any?>, where, setOf("type", "required", "default"))
        val type = ParamType.of(m["type"] as? String ?: abort("$where.type", "missing 'type'"))
            ?: abort("$where.type", "unknown type '${m["type"]}' (${ParamType.entries.joinToString { it.key }})")
        val required = m["required"] as? Boolean
        val hasDefault = m.containsKey("default")
        if (required == true && hasDefault) abort(where, "a required param carries no default")
        if (required != true && !hasDefault) abort(where, "param '$name' needs either 'required: true' or an explicit identity 'default' (no hidden defaults)")
        if (hasDefault) typeCheck(type, m["default"], "$where.default")
        return ParamSpec(name, type, required == true, m["default"])
    }

    /** A value for a param of [type]; a CEL string (`= ...`) always passes, its class is judged in the caller. */
    internal fun typeCheck(type: ParamType, value: Any?, where: String) {
        if (value is String && value.startsWith("= ")) return
        val ok = when (type) {
            ParamType.TICKS -> value is Number && value.toDouble() >= 0.0 && value.toDouble() == Math.floor(value.toDouble())
            ParamType.BLOCKS, ParamType.NUMBER -> value is Number
            ParamType.BOOL -> value is Boolean
            ParamType.STRING -> value is String
        }
        if (!ok) abort(where, "expected ${type.key}, got ${value?.let { "'$it'" } ?: "null"}")
    }

    private val STATE_ID = Regex("[A-Za-z][A-Za-z0-9_-]*")

    private fun machine(raw: Any?, where: String): Machine {
        val m = raw.asMap(where)
        checkKeys(m, where, setOf("start", "states", "world"))
        val start = m["start"] as? String ?: abort("$where.start", "missing 'start'")
        val statesRaw = m["states"].asMap("$where.states")
        if (statesRaw.isEmpty()) abort("$where.states", "a machine needs at least one state")
        val flat = LinkedHashMap<String, MachineState>()
        for ((id, s) in statesRaw) state(id, s, "$where.states.$id", flat)
        if ('/' in start) abort("$where.start", "StartNamesChild: machine.start '$start' must name a top-level state, not a child")
        if (start !in flat || '/' in start) abort("$where.start", "start state '$start' is not defined")
        // then / to resolve lexically: a sibling of the owning state first, then outward, then top level (R36 nesting).
        fun resolve(ref: String, owner: String, at: String): String {
            var scope = owner.substringBeforeLast('/', "")
            while (true) {
                val cand = if (scope.isEmpty()) ref else "$scope/$ref"
                if (cand in flat) return cand
                if (scope.isEmpty()) abort(at, "unknown state '$ref'")
                scope = scope.substringBeforeLast('/', "")
            }
        }
        val states = LinkedHashMap<String, MachineState>()
        for ((id, s) in flat) {
            states[id] = s.copy(
                then = s.then?.let { resolve(it, id, "$where.states.$id.then") },
                on = s.on.mapIndexed { i, t -> t.copy(to = resolve(t.to, id, "$where.states.$id.on[$i].to")) },
            )
        }
        return Machine(start, states, world = ops(m["world"], "$where.world"))
    }

    /** Parses state [id] (a flat path) and, recursively, its `states:` children into [out] as `<id>/<child>`. */
    private fun state(id: String, raw: Any?, where: String, out: MutableMap<String, MachineState>) {
        val m = raw.asMap(where)
        if ("machine" in m) abort("$where.machine", "nested machine: is not supported; declare child states with states: and start:")
        checkKeys(m, where, setOf("for", "then", "final", "enter", "do", "exit", "on", "use", "with", "frames", "start", "states", "parallel"))
        val local = id.substringAfterLast('/')
        if (!STATE_ID.matches(local)) abort(where, "state id '$local' must match [A-Za-z][A-Za-z0-9_-]*")
        val final = m["final"] as? Boolean ?: false
        if (m["final"] != null && m["final"] !is Boolean) abort("$where.final", "final must be true or false")
        val use = m["use"]
        if (use != null) {
            val bad = listOf("for", "final", "enter", "do", "exit", "states", "start", "parallel").firstOrNull { it in m }
            if (bad != null) abort("$where.$bad", "a state that calls a part (use:) carries only with / frames / on / then")
        } else {
            for (k in listOf("with", "frames")) if (k in m) abort("$where.$k", "'$k' is only valid next to use:")
        }
        if (final) {
            val bad = listOf("for", "then", "on", "exit", "use", "states", "start", "parallel").firstOrNull { it in m }
            if (bad != null) abort("$where.$bad", "a final state has no outgoing transitions, exit or children")
        }
        val childrenRaw = m["states"]?.asMap("$where.states")
        val start = m["start"]
        if (childrenRaw == null) {
            if (start != null) abort("$where.start", "start: is only valid next to states:")
        } else {
            if (childrenRaw.isEmpty()) abort("$where.states", "a state with states: needs at least one child")
            if (start == null) abort("$where.start", "NestedStartMissing: state '$local' has states: and must declare start: <child>")
            if (start !is String) abort("$where.start", "start must be a child state name")
        }
        // ADR-0303 10.2: `parallel: [machine, ...]` regions all run and the state ends when every one is final; a region is a machine
        // of its own, so it cannot share a state with child `states:` (two ways to say what is entered first)
        val parallel = m["parallel"]?.let { raw ->
            if (childrenRaw != null) abort("$where.parallel", "a state has either states: (one active child) or parallel: (all regions), not both")
            val list = raw as? List<*> ?: abort("$where.parallel", "parallel must be a list of machines {start, states}")
            if (list.isEmpty()) abort("$where.parallel", "parallel needs at least one region")
            list.mapIndexed { i, r -> machine(r, "$where.parallel[$i]") }
        } ?: emptyList()
        val forTicks = m["for"]
        if (forTicks != null) {
            if (!(forTicks is Number && forTicks.toDouble() >= 0 && forTicks.toDouble() == Math.floor(forTicks.toDouble())) && !(forTicks is String && forTicks.startsWith("= "))) {
                abort("$where.for", "for must be a non-negative integer of ticks or a CEL string, got '$forTicks'")
            }
        }
        val then = m["then"] as String?
        if (m["then"] != null && m["then"] !is String) abort("$where.then", "then must be a state id")
        val on = (m["on"] as? List<*>)?.mapIndexed { i, t -> transition(t, "$where.on[$i]") }
            ?: if (m["on"] == null) emptyList() else abort("$where.on", "on must be a list of transitions")
        val call = use?.let {
            val part = it as? String ?: abort("$where.use", "use must be a part id")
            PartCall(part, m["with"].let { w -> if (w == null) emptyMap() else w.asMap("$where.with") }, stringMap(m["frames"], "$where.frames"))
        }
        out[id] = MachineState(id, forTicks, then, final, ops(m["enter"], "$where.enter"), ops(m["do"], "$where.do"), ops(m["exit"], "$where.exit"), on, call, parallel = parallel, start = start as String?)
        childrenRaw?.forEach { (cid, c) -> state("$id/$cid", c, "$where.states.$cid", out) }
    }

    private fun transition(raw: Any?, where: String): MachineTransition {
        val m = raw.asMap(where)
        checkKeys(m, where, setOf("event", "after", "when", "to", "do", "absorb"))
        val event = m["event"] as String?
        if (m["event"] != null && m["event"] !is String) abort("$where.event", "event must be a name")
        val after = m["after"]
        if ((event == null) == (after == null)) abort(where, "a transition has exactly one of 'event' or 'after'")
        if (after != null && !(after is Number && after.toDouble() >= 0) && !(after is String && after.startsWith("= "))) abort("$where.after", "after must be ticks or a CEL string")
        val to = m["to"] as? String ?: abort("$where.to", "missing 'to'")
        val whenExpr = m["when"] as String?
        if (whenExpr != null && !whenExpr.startsWith("= ")) abort("$where.when", "when must be a CEL string starting with '= '")
        val absorb = m["absorb"]?.let { a ->
            if (a != true) abort("$where.absorb", "absorb is written only as 'absorb: true' (a false flag is dead text; delete the key)")
            if (event != "hurt" && event?.startsWith("hurt.") != true) abort(where, "transition on '${event ?: "tick"}': absorb only applies to a hurt event (the hit that would damage the caster is cancelled)")
            true
        } ?: false
        return MachineTransition(event, after, whenExpr, to, ops(m["do"], "$where.do"), absorb)
    }

    private fun ops(raw: Any?, where: String): List<OpMap> {
        if (raw == null) return emptyList()
        val list = SeqSugar.expandList(raw as? List<*> ?: abort(where, "expected a list of ops"), where) { p, msg -> abort(p, msg) }
        return list.flatMapIndexed { i, e ->
            val m = e.asMap("$where[$i]")
            if (m["op"] !is String && m["emit"] !is String && m["use"] !is String) {
                // ADR-0303 2.0a: a timing group is implied by `do:`
                if (m["do"] is List<*>) return@flatMapIndexed listOf(m + ("op" to "group"))
                abort("$where[$i]", "an op needs 'op: <name>' (or the shorthands 'emit: <event>' / 'use: <part>'; a timing group is a map with `do:`)")
            }
            listOf(m)
        }
    }

    private fun checkKeys(m: Map<String, Any?>, where: String, allowed: Set<String>) {
        val unknown = m.keys.firstOrNull { it !in allowed }
        if (unknown != null) abort("$where.$unknown", "unknown key '$unknown' (allowed: ${allowed.sorted().joinToString()})")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(where: String): Map<String, Any?> = this as? Map<String, Any?> ?: abort(where, "expected a mapping")

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMapOrEmpty(where: String): Map<String, Any?> = if (this == null) emptyMap() else this as? Map<String, Any?> ?: abort(where, "expected a mapping")

    private fun stringMap(raw: Any?, where: String): Map<String, String> {
        if (raw == null) return emptyMap()
        return raw.asMap(where).mapValues { (k, v) -> v as? String ?: abort("$where.$k", "expected a string") }
    }
}
