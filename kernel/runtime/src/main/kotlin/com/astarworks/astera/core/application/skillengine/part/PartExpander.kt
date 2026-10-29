package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.part.EventLink
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.MachineTransition
import com.astarworks.astera.core.domain.skillengine.part.OpMap
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.part.PartError

/**
 * ADR-0303 section 9 static expansion, tree to tree: every `use:` state is replaced by the called part's states,
 * ids prefixed with the call id (`guard/parry_window`), op ids prefixed with their state path, params and frame names
 * substituted. The result is a flat [Machine] with no `use:` left. Raises on an unknown part, a call cycle, nesting
 * deeper than [maxDepth] (default 6, `max_part_depth`), a missing or ill-typed argument, an unbound frame.
 *
 * Events: a part's `{emit: x}` becomes `<call path>.x`; every final state of a level raises `end` (root) or
 * `<call path>.end`. A caller transition `{event: <call>.end, to: s}` (or `then: s` on the call state) is folded into the
 * part's final states as an immediate `after: 0` transition, so it adds no tick. Caller transitions on any other event
 * of the call, or on an external event while the call runs (preemption), cannot be folded and are returned in
 * [Machine.links]: they need the runtime Transition op (WP-D D4).
 */
public class PartExpander(private val parts: Map<String, Part>, private val maxDepth: Int = 6) {

    public fun expand(machine: Machine): Result<Machine, PartError> = try {
        val out = Out()
        val entry = level(machine, "", "", 0, emptyList(), out).first
        Result.success(Machine(entry, out.states, out.links, machine.world, machine.tail))
    } catch (e: PartAbort) {
        Result.failure(e.error)
    }

    private class Out {
        val states = LinkedHashMap<String, MachineState>()
        val links = ArrayList<EventLink>()
    }

    private class Pending(val finals: List<String>, val callEv: String)

    /** Expands one machine level into [out]; returns the full id of its entry state and the full ids of its final states. */
    private fun level(m: Machine, prefix: String, evPrefix: String, depth: Int, stack: List<String>, out: Out): Pair<String, List<String>> {
        val entry = LinkedHashMap<String, String>()
        val pending = HashMap<String, Pending>()
        for (s in m.states.values) {
            val call = s.use
            if (call == null) {
                entry[s.id] = prefix + s.id
                continue
            }
            val where = prefix + s.id
            val part = parts[call.part] ?: throw PartAbort(PartError.UnknownPart(where, call.part))
            if (part.id in stack) throw PartAbort(PartError.Cycle(where, stack.dropWhile { it != part.id } + part.id))
            if (depth + 1 > maxDepth) throw PartAbort(PartError.TooDeep(where, maxDepth))
            val bound = Binding.bind(part, readSiblingOut(call, m), where)
            val callEv = join(evPrefix, s.id)
            val (childEntry, finals) = level(bound, "$where/", callEv, depth + 1, stack + part.id, out)
            entry[s.id] = childEntry
            pending[s.id] = Pending(finals, callEv)
        }

        val myFinals = ArrayList<String>()
        for (s in m.states.values) {
            if (s.use != null) continue
            val path = prefix + s.id
            val localIds = (s.enter + s.doOps + s.exit).mapNotNull { it["id"] as? String }.toSet()
            val enter = qualify(s.enter, path, "enter", evPrefix, localIds).toMutableList()
            if (s.final) {
                enter += mapOf("emit" to if (evPrefix.isEmpty()) "end" else "$evPrefix.end")
                myFinals += path
            }
            out.states[path] = MachineState(
                id = path,
                forTicks = s.forTicks,
                then = s.then?.let { entry.getValue(it) },
                final = s.final,
                enter = enter,
                doOps = qualify(s.doOps, path, "do", evPrefix, localIds),
                exit = qualify(s.exit, path, "exit", evPrefix, localIds),
                on = s.on.mapIndexed { i, t -> remap(t, entry, path, i, evPrefix, localIds) },
                use = null,
                parallel = s.parallel.map { region(it, evPrefix, depth, stack, out) },
                start = s.start,
            )
        }

        // Fold each call's continuation into the called part's final states.
        for (s in m.states.values) {
            val p = pending[s.id] ?: continue
            val endEvent = "${s.id}.end"
            val cont = ArrayList<MachineTransition>()
            s.then?.let { cont += MachineTransition(null, 0, null, entry.getValue(it), emptyList()) }
            s.on.forEachIndexed { i, t ->
                val mapped = remap(t, entry, prefix + s.id, i, evPrefix, emptySet())
                when {
                    t.event == endEvent -> cont += MachineTransition(null, 0, mapped.whenExpr, mapped.to, mapped.effects)
                    t.event != null && t.event!!.startsWith("${s.id}.") ->
                        out.links += EventLink(p.callEv, join(evPrefix, t.event!!), mapped, "caller reacts to an event of the call while it runs")
                    else ->
                        out.links += EventLink(p.callEv, t.event ?: "after:${t.afterTicks}", mapped, "caller transition preempts the call")
                }
            }
            if (cont.isNotEmpty()) {
                for (f in p.finals) {
                    val st = out.states.getValue(f)
                    out.states[f] = st.copy(final = false, on = st.on + cont)
                }
            }
        }
        return entry.getValue(m.start) to myFinals
    }

    /** A `parallel` region is a machine of its own: expanded with local ids (MachineTables prefixes them with the parent path); its links join the call's. */
    private fun region(r: Machine, evPrefix: String, depth: Int, stack: List<String>, out: Out): Machine {
        val sub = Out()
        val entry = level(r, "", evPrefix, depth, stack, sub).first
        out.links += sub.links
        return Machine(entry, sub.states, emptyList(), r.world)
    }

    /**
     * ADR-0303 section 9 (`frames_out`): a call's frame binding `<sibling call>.<out name>` reads a frame the sibling part publishes. It names the inner
     * frame the sibling declared under `frames_out` (the anchor registry resolves it by its op id, as it does for any frame of the same skill).
     */
    private fun readSiblingOut(call: com.astarworks.astera.core.domain.skillengine.part.PartCall, level: Machine): com.astarworks.astera.core.domain.skillengine.part.PartCall {
        if (call.frames.isEmpty()) return call
        return call.copy(frames = call.frames.mapValues { (_, v) ->
            val sibling = level.states[v.substringBefore('.')]?.use?.let { parts[it.part] }
            val out = v.substringAfter('.', "").substringBefore('.')
            val inner = sibling?.framesOut?.get(out)
            if (inner == null) v else inner + v.substring(v.substringBefore('.').length + 1 + out.length)
        })
    }

    private fun join(prefix: String, name: String): String = if (prefix.isEmpty()) name else "$prefix.$name"

    private fun remap(t: MachineTransition, entry: Map<String, String>, path: String, index: Int, evPrefix: String, localIds: Set<String>): MachineTransition =
        t.copy(to = entry.getValue(t.to), event = t.event?.let { localEvent(it, path, localIds) }, effects = qualify(t.effects, path, "t$index", evPrefix, localIds))

    /** `bolt.end` written in a state that spawns op `bolt` names that op's instance `<state>/bolt` (op ids are state-qualified above). */
    private fun localEvent(event: String, path: String, localIds: Set<String>): String {
        val base = event.substringBefore('.')
        return if (event.contains('.') && base in localIds) "$path/$event" else event
    }

    /** Prefixes op ids with the state path (+ phase for transitions) and qualifies `emit:` names with the call path. */
    private fun qualify(ops: List<OpMap>, path: String, phase: String, evPrefix: String, localIds: Set<String>): List<OpMap> = ops.map { op ->
        val out = LinkedHashMap(op)
        (op["id"] as? String)?.let { out["id"] = if (phase.startsWith("t")) "$path/$phase/$it" else "$path/$it" }
        (op["emit"] as? String)?.let { out["emit"] = join(evPrefix, it) }
        (op["until"] as? String)?.let { out["until"] = localEvent(it, path, localIds) }
        out
    }
}

/** Parameter and frame binding of one part call, applied to a deep copy of the part's machine. */
internal object Binding {
    fun bind(part: Part, call: com.astarworks.astera.core.domain.skillengine.part.PartCall, where: String): Machine {
        val values = LinkedHashMap<String, Any?>()
        for (k in call.args.keys) if (k !in part.params) abort("$where.with.$k", "part '${part.id}' has no param '$k' (${part.params.keys.joinToString()})")
        for ((name, spec) in part.params) {
            if (name in call.args) {
                PartParser.typeCheck(spec.type, call.args[name], "$where.with.$name")
                values[name] = call.args[name]
            } else if (spec.required) {
                abort("$where.with.$name", "missing required param '$name' (${spec.type.key}) of part '${part.id}'")
            } else {
                values[name] = spec.default
            }
        }
        for (k in call.frames.keys) if (k !in part.framesIn) abort("$where.frames.$k", "part '${part.id}' takes no frame '$k' (${part.framesIn.keys.joinToString()})")
        for (k in part.framesIn.keys) if (k !in call.frames) abort("$where.frames.$k", "frame '$k' of part '${part.id}' is not bound")
        val env = Env(values, call.frames)
        val m = part.machine
        if (m.world.isNotEmpty()) abort("$where.world", "part '${part.id}' declares machine.world: only a skill machine owns cast-level rows")
        return Machine(
            start = m.start,
            states = m.states.mapValues { (_, s) -> state(s, env) },
        )
    }

    private fun state(s: MachineState, env: Env): MachineState = s.copy(
        forTicks = env.value(s.forTicks),
        enter = s.enter.map { env.op(it) },
        doOps = s.doOps.map { env.op(it) },
        exit = s.exit.map { env.op(it) },
        on = s.on.map { t ->
            t.copy(afterTicks = env.value(t.afterTicks), whenExpr = t.whenExpr?.let { w -> env.value(w).let { r -> if (r is String) r else "= $r" } }, effects = t.effects.map { env.op(it) })
        },
        use = s.use?.let { c ->
            c.copy(
                args = c.args.mapValues { (_, v) -> env.value(v) },
                frames = c.frames.mapValues { (_, v) -> env.frame(v) },
            )
        },
    )

    class Env(private val params: Map<String, Any?>, private val frames: Map<String, String>) {
        private companion object {
            /** Op fields typed as a closed keyword set whose words can equal a frame name (`center`, `fixed`...). */
            val ENUM_KEYS = setOf("billboard", "alignment", "pivot", "face", "mode", "kind", "direction", "forward", "targets", "category", "context", "operation", "tool", "law")
        }

        @Suppress("UNCHECKED_CAST")
        fun op(op: OpMap): OpMap = value(op) as OpMap

        fun frame(s: String): String {
            for ((name, bound) in frames) {
                if (s == name) return bound
                if (s.startsWith("$name.")) return bound + s.substring(name.length)
            }
            return s
        }

        fun value(v: Any?): Any? = when (v) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
                // an enum-typed field (`billboard: center`) names a keyword, never a frame, even when the part binds a frame of that name
                v.forEach { (k, x) -> out[k as String] = if (k in ENUM_KEYS && x is String && !x.startsWith("= ")) x else value(x) }
            }
            is List<*> -> v.map { value(it) }
            is String -> string(v)
            else -> v
        }

        private fun string(s: String): Any? {
            if (!s.startsWith("= ")) return frame(s)
            val body = s.substring(2).trim()
            if (body in params) return params[body]
            var out = body
            for ((name, v) in params) {
                out = out.replace(Regex("(?<![\\w.])${Regex.escape(name)}(?![\\w(])"), Regex.escapeReplacement(render(v)))
            }
            for ((name, bound) in frames) {
                out = out.replace(Regex("(?<![\\w.])${Regex.escape(name)}(?=\\.)"), Regex.escapeReplacement(bound))
            }
            return "= $out"
        }

        private fun render(v: Any?): String = when (v) {
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            is Number -> v.toString()
            is Boolean -> v.toString()
            is String -> if (v.startsWith("= ")) "(${v.substring(2).trim()})" else "'$v'"
            else -> "null"
        }
    }
}
