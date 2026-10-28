package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.MachineTransition
import com.astarworks.astera.core.domain.skillengine.part.OpMap
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.part.PartError

/** A piece of the automaton that existing ops cannot express (needs the runtime Transition op, WP-D D4). */
public data class Unlowered(val state: String, val reason: String)

/**
 * [timeline]: the `gameplay.timeline` entries. [presentationOn]: `presentation.on` handlers keyed by the event the
 * gameplay side raises where the state's visual ops start. [unlowered]: what was NOT lowered (never silently dropped).
 */
public data class LoweredMachine(
    val timeline: List<OpMap>,
    val presentationOn: Map<String, List<OpMap>>,
    val unlowered: List<Unlowered>,
)

/**
 * ADR-0303 section 10 lowering of a flat (expanded) [Machine] to the existing v2 ops, at exactly the same ticks:
 *
 * | machine | existing ops |
 * |---|---|
 * | enter / do ops | the ops themselves, at state entry (op ids sanitised: `/` and `.` become `_`) |
 * | `{emit: x}` | `raise_event` x |
 * | `for: N` + `then: s` / `after: N` | `delay` N whose body is exit ops, transition effects, entry of s |
 * | `after: 0` / `then:` without `for` | the same body inlined (a `choose` when guarded); no tick is added |
 * | `on: {event: hurt}` for `for: N` | `window` listen incoming_hit, ticks N, once, filter = guard, body as above; `event.attacker` becomes `victim` |
 * | final | nothing (its `end` emit is an ordinary enter op) |
 * | display / sound / particle ... | presentation: a `raise_event <state path>@enter|exit` where they start, ops under `presentation.on` |
 *
 * Not lowerable with today's ops (reported in [LoweredMachine.unlowered], never dropped silently): any event other than
 * `hurt`; a timer racing an event transition (the timer must be cancelled when the event wins: needs a state variable);
 * several delayed timers; guarded delayed timers; loops; [Machine.links].
 */
public object MachineLowerer {
    private val PRESENTATION_OPS = setOf(
        "display", "sound", "particle", "emit", "ribbon", "mesh", "plate", "block", "quad", "line", "trail", "track", "remove", "rig",
        "title", "action_bar", "camera_shake", "screen_flash", "screen_veil", "stop_sound", "caster_anim", "victim_anim", "glow",
    )

    public fun lower(machine: Machine): LoweredMachine {
        val l = Lowering(machine)
        val body = l.entry(machine.start, emptySet())
        machine.links.forEach { l.unlowered += Unlowered(it.callPath, "${it.reason}: event '${it.event}' -> ${it.transition.to}") }
        return LoweredMachine(listOf(mapOf("id" to "t0", "at" to 0, "do" to body)), l.presentation, l.unlowered)
    }

    internal fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_]"), "_")

    private class Lowering(val m: Machine) {
        val presentation = LinkedHashMap<String, List<OpMap>>()
        val unlowered = ArrayList<Unlowered>()
        private val ids = HashSet<String>()

        fun uniqueId(base: String): String {
            var id = sanitize(base)
            var n = 2
            while (!ids.add(id)) id = sanitize(base) + "_" + n++
            return id
        }

        fun entry(sid: String, visiting: Set<String>): List<OpMap> {
            if (sid in visiting) {
                unlowered += Unlowered(sid, "cycle in the state graph cannot be lowered to a finite op list")
                return emptyList()
            }
            val s = m.states.getValue(sid)
            val seen = visiting + sid
            val ops = ArrayList<OpMap>()
            ops += phase(s, s.enter + s.doOps, "enter")
            if (s.final) return ops

            val events = s.on.filter { it.event != null }
            val timers = ArrayList<MachineTransition>()
            s.then?.let { timers += MachineTransition(null, s.forTicks ?: 0, null, it, emptyList()) }
            timers += s.on.filter { it.afterTicks != null }
            val immediate = timers.filter { isZero(it.afterTicks) }
            val delayed = timers - immediate.toSet()

            if (events.isNotEmpty()) {
                val ticks = s.forTicks ?: delayed.firstOrNull()?.afterTicks
                if (ticks == null) {
                    unlowered += Unlowered(sid, "waits for ${events.joinToString { it.event!! }} with no timeout")
                } else {
                    ops += windows(s, events, ticks, seen)
                }
                if (timers.isNotEmpty()) {
                    unlowered += Unlowered(
                        sid,
                        "timer ${timers.joinToString { "after ${it.afterTicks} -> ${it.to}" }} races the event transition; " +
                            "it must be cancelled when the event wins (needs the D4 Transition op)",
                    )
                }
                return ops
            }
            if (immediate.isNotEmpty()) {
                ops += immediateOps(s, immediate, seen)
            }
            if (delayed.size == 1 && delayed[0].whenExpr == null) {
                val t = delayed[0]
                ops += mapOf("id" to uniqueId("${sid}_delay"), "op" to "delay", "after" to t.afterTicks, "do" to transitionBody(s, t, seen))
            } else if (delayed.isNotEmpty()) {
                unlowered += Unlowered(sid, "${delayed.size} delayed timers (or a guarded one) need the D4 Transition op")
            }
            return ops
        }

        private fun immediateOps(s: MachineState, ts: List<MachineTransition>, seen: Set<String>): List<OpMap> {
            if (ts.size == 1 && ts[0].whenExpr == null) return transitionBody(s, ts[0], seen)
            val cases = ts.map { t -> mapOf("when" to (t.whenExpr ?: "= true"), "do" to transitionBody(s, t, seen)) }
            return listOf(mapOf("id" to uniqueId("${s.id}_choose"), "op" to "choose", "cases" to cases))
        }

        private fun windows(s: MachineState, events: List<MachineTransition>, ticks: Any?, seen: Set<String>): List<OpMap> {
            val out = ArrayList<OpMap>()
            val byEvent = events.groupBy { it.event!! }
            for ((event, ts) in byEvent) {
                if (event != "hurt") {
                    ts.forEach { unlowered += Unlowered(s.id, "event '$event' -> ${it.to} needs the D4 Transition op (only hurt maps to a window)") }
                    continue
                }
                val w = LinkedHashMap<String, Any?>()
                w["id"] = uniqueId("${s.id}_win")
                w["op"] = "window"
                w["listen"] = "incoming_hit"
                w["ticks"] = ticks
                w["once"] = true
                if (ts.size == 1) {
                    ts[0].whenExpr?.let { w["filter"] = attacker(it) }
                    w["do"] = attacker(transitionBody(s, ts[0], seen))
                } else {
                    val cases = ts.map { t -> mapOf("when" to attacker(t.whenExpr ?: "= true"), "do" to attacker(transitionBody(s, t, seen))) }
                    w["do"] = listOf(mapOf("id" to uniqueId("${s.id}_choose"), "op" to "choose", "cases" to cases))
                }
                out += w
            }
            return out
        }

        private fun transitionBody(s: MachineState, t: MachineTransition, seen: Set<String>): List<OpMap> =
            phase(s, s.exit, "exit") + phase(s, t.effects, "fx") + entry(t.to, seen)

        /** Gameplay ops stay inline; visual ops move to `presentation.on` behind one raised event; `{emit:}` is a raise_event. */
        private fun phase(s: MachineState, ops: List<OpMap>, phase: String): List<OpMap> {
            val out = ArrayList<OpMap>()
            val visual = ArrayList<OpMap>()
            val event = s.id.replace('/', '.') + "@" + (if (phase == "exit") "exit" else if (phase == "fx") "fx" else "enter")
            var raised = false
            for (op in ops) {
                val emit = op["emit"] as? String
                val name = op["op"] as? String
                when {
                    emit != null -> out += mapOf("id" to uniqueId(op["id"] as? String ?: "${s.id}_emit_$emit"), "op" to "raise_event", "name" to emit)
                    name in PRESENTATION_OPS -> {
                        if (!raised) {
                            raised = true
                            out += mapOf("id" to uniqueId("${s.id}_fx_$phase"), "op" to "raise_event", "name" to event)
                        }
                        visual += withId(op, s.id, phase)
                    }
                    else -> out += withId(op, s.id, phase)
                }
            }
            if (visual.isNotEmpty()) presentation[event] = (presentation[event].orEmpty() + visual)
            return out
        }

        private fun withId(op: OpMap, state: String, phase: String): OpMap {
            val out = LinkedHashMap(op)
            val given = op["id"] as? String
            out["id"] = uniqueId(given ?: "${state}_${phase}_${op["op"]}")
            return out
        }

        private fun isZero(v: Any?): Boolean = v is Number && v.toDouble() == 0.0

        @Suppress("UNCHECKED_CAST")
        private fun <T> attacker(v: T): T = replace(v) as T

        private fun replace(v: Any?): Any? = when (v) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> v.forEach { (k, x) -> out[k as String] = replace(x) } }
            is List<*> -> v.map { replace(it) }
            is String -> v.replace(Regex("(?<![\\w.])event\\.attacker(?![\\w])"), "victim")
            else -> v
        }
    }
}

/**
 * The whole pipeline for one skill tree: parse `machine:`, expand parts, lint, lower, merge into `gameplay.timeline` /
 * `presentation.on`. This is the single entry point the skill composer calls (see report: one call-site line).
 */
public object SkillMachines {
    /** Returns [tree] unchanged when it has no `machine:`. [strict]: anything in [LoweredMachine.unlowered] is a failure. */
    public fun lower(tree: Map<String, Any?>, parts: Map<String, Part>, strict: Boolean = true): Result<Map<String, Any?>, PartError> {
        val parsed = PartParser.skillMachine(tree)
        val machine = when (parsed) {
            is Result.Failure -> return Result.failure(parsed.error)
            is Result.Success -> parsed.value ?: return Result.success(tree)
        }
        val expanded = when (val r = PartExpander(parts).expand(machine)) {
            is Result.Failure -> return Result.failure(r.error)
            is Result.Success -> r.value
        }
        MachineLint.lint(expanded).firstOrNull()?.let { return Result.failure(it) }
        val lowered = MachineLowerer.lower(expanded)
        if (strict) lowered.unlowered.firstOrNull()?.let { return Result.failure(PartError.Lint(it.state, "not lowerable: ${it.reason}")) }

        val out = LinkedHashMap(tree)
        out.remove("machine")
        @Suppress("UNCHECKED_CAST")
        val gameplay = LinkedHashMap((tree["gameplay"] as? Map<String, Any?>).orEmpty())
        gameplay["timeline"] = (gameplay["timeline"] as? List<*>).orEmpty() + lowered.timeline
        out["gameplay"] = gameplay
        if (lowered.presentationOn.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val pres = LinkedHashMap((tree["presentation"] as? Map<String, Any?>).orEmpty())

            @Suppress("UNCHECKED_CAST")
            val on = LinkedHashMap((pres["on"] as? Map<String, Any?>).orEmpty())
            on.putAll(lowered.presentationOn)
            pres["on"] = on
            out["presentation"] = pres
        }
        return Result.success(out)
    }
}
