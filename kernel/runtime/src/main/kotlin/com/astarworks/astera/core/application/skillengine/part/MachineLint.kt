package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.PartError

/**
 * ADR-0303 10.4 checks 1-3 and 8 on an EXPANDED machine: unreachable states, dead ends / states that cannot reach a
 * final state (unterminated), and unbounded timing (an event wait with no timeout, a non-constant `for`, a cycle with
 * no timer on it).
 */
public object MachineLint {
    public fun lint(m: Machine): List<PartError.Lint> {
        val out = ArrayList<PartError.Lint>()
        // a state with children enters its start child, so that child is a successor of the parent (R36)
        val succ = m.states.mapValues { (_, s) -> successors(s) + listOfNotNull(s.start?.let { "${s.id}/$it" }) }

        val reachable = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(m.start))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (reachable.add(id)) queue += succ[id].orEmpty()
        }
        for (id in m.states.keys) if (id !in reachable) out += PartError.Lint(id, "state '$id' is unreachable from start '${m.start}'")

        for (s in m.states.values) {
            if (s.id !in reachable) continue
            if (!s.final && succ[s.id].orEmpty().isEmpty()) out += PartError.Lint(s.id, "state '${s.id}' has no way out and is not final (unterminated)")
            if (s.on.any { it.event != null } && s.forTicks == null && s.on.none { it.afterTicks != null }) {
                out += PartError.Lint(s.id, "state '${s.id}' waits for an event with no timeout (unbounded)")
            }
            for (v in listOf(s.forTicks) + s.on.map { it.afterTicks }) {
                if (v != null && v !is Number) out += PartError.Lint(s.id, "state '${s.id}' has a non-constant timer '$v' (unbounded: bind it to a constant)")
            }
        }

        // every reachable state must be able to reach a final state
        val canFinish = HashSet<String>()
        var grew = true
        while (grew) {
            grew = false
            for (id in reachable) {
                if (id in canFinish) continue
                val s = m.states.getValue(id)
                if (s.final || succ[id].orEmpty().any { it in canFinish }) { canFinish += id; grew = true }
            }
        }
        for (id in reachable) {
            if (id !in canFinish && succ[id].orEmpty().isNotEmpty()) out += PartError.Lint(id, "no path from '$id' reaches a final state (unterminated)")
        }

        // a cycle needs a state with a positive timer on it, otherwise it can spin inside one tick
        for (scc in components(m, succ)) {
            val cyclic = scc.size > 1 || scc.single() in succ[scc.single()].orEmpty()
            if (!cyclic) continue
            if (scc.none { hasPositiveTimer(m.states.getValue(it)) }) {
                out += PartError.Lint(scc.first(), "cycle ${scc.joinToString(" -> ")} has no timer on it (unbounded loop)")
            }
        }
        return out
    }

    private fun successors(s: MachineState): List<String> = (listOfNotNull(s.then) + s.on.map { it.to }).distinct()

    private fun hasPositiveTimer(s: MachineState): Boolean =
        (s.forTicks as? Number)?.let { it.toDouble() > 0 } == true || s.on.any { (it.afterTicks as? Number)?.let { n -> n.toDouble() > 0 } == true }

    /** Strongly connected components (Kosaraju). */
    private fun components(m: Machine, succ: Map<String, List<String>>): List<List<String>> {
        val order = ArrayList<String>()
        val seen = HashSet<String>()
        fun dfs(n: String) {
            if (!seen.add(n)) return
            succ[n].orEmpty().forEach(::dfs)
            order += n
        }
        m.states.keys.forEach(::dfs)
        val rev = HashMap<String, MutableList<String>>()
        for ((a, bs) in succ) for (b in bs) rev.getOrPut(b) { ArrayList() } += a
        val done = HashSet<String>()
        val out = ArrayList<List<String>>()
        for (n in order.reversed()) {
            if (n in done) continue
            val comp = ArrayList<String>()
            val stack = ArrayDeque(listOf(n))
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                if (!done.add(x)) continue
                comp += x
                stack += rev[x].orEmpty()
            }
            out += comp
        }
        return out
    }
}
