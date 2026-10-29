package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.OpMap

/**
 * The `expand` text dump of ADR-0303 section 11 for an EXPANDED machine: per state its tick window, branches, firings and
 * hits, then min/max path lengths from start to every final state. Generated, never hand-written.
 */
public object MachineDump {
    private class Edge(val label: String, val to: String, val min: Int, val max: Int)

    public fun render(m: Machine): String {
        val sb = StringBuilder()
        val order = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(m.start))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (order.add(id)) edges(m.states.getValue(id)).forEach { queue += it.to }
        }
        for (id in order) {
            val s = m.states.getValue(id)
            val d = duration(s)
            sb.append("state ").append(id).append("   ticks 0-").append(d ?: "?").append(if (s.final) "   final" else "").append('\n')
            val es = edges(s)
            if (es.isNotEmpty()) sb.append("  branches: ").append(es.joinToString(" | ") { "${it.label} -> ${it.to}" }).append('\n')
            val firings = firings(s, d ?: 0)
            if (firings.isNotEmpty()) sb.append("  firings: ").append(firings.joinToString(" | ")).append('\n')
            val hits = (s.enter + s.doOps + s.exit + s.on.flatMap { it.effects }).count { it["op"] == "damage" || it["op"] == "hit" }
            if (hits > 0) sb.append("  hits: ").append(hits).append('\n')
        }
        m.links.forEach { sb.append("link ").append(it.callPath).append(": ").append(it.event).append(" -> ").append(it.transition.to).append("   (").append(it.reason).append(")\n") }
        val paths = ArrayList<String>()
        collect(m, m.start, listOf(m.start), 0, 0, paths)
        sb.append("paths:\n")
        paths.forEach { sb.append("  ").append(it).append('\n') }
        return sb.toString()
    }

    private fun collect(m: Machine, id: String, trail: List<String>, min: Int, max: Int, out: MutableList<String>) {
        if (out.size >= 64) return
        val s = m.states.getValue(id)
        val es = edges(s)
        if (s.final || es.isEmpty()) {
            out += "${trail.joinToString(" -> ")} = min $min max $max"
            return
        }
        for (e in es) {
            if (e.to in trail) { out += "${trail.joinToString(" -> ")} -> ${e.to} = loop"; continue }
            collect(m, e.to, trail + e.to, min + e.min, max + e.max, out)
        }
    }

    private fun duration(s: MachineState): Int? {
        (s.forTicks as? Number)?.let { return it.toInt() }
        if (s.forTicks != null) return null
        val after = s.on.mapNotNull { it.afterTicks }
        if (after.isEmpty()) return 0
        return (after.minByOrNull { (it as? Number)?.toDouble() ?: Double.MAX_VALUE } as? Number)?.toInt()
    }

    private fun edges(s: MachineState): List<Edge> {
        val d = duration(s) ?: 0
        val out = ArrayList<Edge>()
        for (t in s.on) {
            if (t.event != null) {
                out += Edge("${t.event}${t.whenExpr?.let { "[${it.removePrefix("= ")}]" } ?: ""}", t.to, 0, d)
            } else {
                val n = (t.afterTicks as? Number)?.toInt() ?: d
                out += Edge("timer($n)${t.whenExpr?.let { "[${it.removePrefix("= ")}]" } ?: ""}", t.to, n, n)
            }
        }
        s.then?.let { out += Edge("timer($d)", it, d, d) }
        return out
    }

    private fun firings(s: MachineState, d: Int): List<String> {
        val out = ArrayList<String>()
        fun add(ops: List<OpMap>, baseTick: Int) {
            for (op in ops) {
                val name = (op["id"] as? String) ?: (op["emit"] as? String)?.let { "emit $it" } ?: continue
                val at = (op["at"] as? Number)?.toInt() ?: 0
                val forT = (op["for"] as? Number)?.toInt()
                val every = (op["every"] as? Number)?.toInt()
                val times = (op["times"] as? Number)?.toInt() ?: if (forT != null && every != null && every > 0) (forT + every - 1) / every else 1
                val tail = if (forT != null && every == null) " (0-$forT)" else ""
                out += "$name @${baseTick + at} x$times$tail"
            }
        }
        add(s.enter, 0)
        add(s.doOps, 0)
        add(s.exit, d)
        return out
    }
}
