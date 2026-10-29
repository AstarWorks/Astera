package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable

/**
 * A rider's laws run for their own fixed length (ADR-0303 3, rev4.13).
 *
 * A frame or display that outlives its state (`until: <other state>.end`: the blades of a thrown volley) is not ended by the state's exit, so
 * the laws it carries are not the state's either: when its `Key` / `Set` writes reach the state's end they are folded to `detach: true` at
 * load (all of them, so an early exit cuts none), the same rows the hand-off already plays on the state's clock. Check 8 then bounds the last of them by
 * `machine.tail`, so the cast end and the visual end stay computable at load (a runtime length would not be).
 *
 * Only rows of an instance that survives its state qualify; a plain frame whose laws run past the state still raises `StateOverrun`.
 */
internal object RiderOwnLength {
    fun fold(states: List<StateTable>, subTables: Map<EventPath, SubTable>): List<StateTable> {
        val ends = LinkedHashMap<InstanceId, MutableSet<String>>()
        for ((path, sub) in subTables) {
            if (!path.value.endsWith(".end")) continue
            val owner = path.value.removeSuffix(".end").substringBeforeLast('/', "")
            for (row in sub.rows) (row.action as? Action.End)?.let { ends.getOrPut(it.instance) { LinkedHashSet() } += owner }
        }
        if (ends.isEmpty()) return states
        return states.map { st ->
            val max = when (val d = st.duration) { is Duration.Fixed -> d.ticks; is Duration.Bounded -> d.range.max }
            fun law(r: Row) = (r.action is Action.Key || r.action is Action.Set) && !r.detached &&
                ends[r.instance]?.any { it != st.state.value && !it.startsWith(st.state.value + "/") } == true
            // one schedule: when any write of the instance falls past the state's end, all of its writes are detached, so an early exit (a hit) cuts none of them
            val running = st.rows.filter { law(it) && it.tick >= max }.map { it.instance }.toSet()
            val rows = st.rows.map { r -> if (law(r) && r.instance in running) r.copy(detached = true) else r }
            if (rows == st.rows) st else st.copy(rows = rows)
        }
    }
}
