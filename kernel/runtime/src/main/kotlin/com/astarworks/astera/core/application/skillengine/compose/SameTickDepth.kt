package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable

/**
 * ADR-0303 10.2a step 4 / check 12: the longest same-tick event chain, counted statically so the table carries the number the player's
 * cascade cap is built from (`cap = sameTickDepth + 1` waves; wave 1 delivers external events and fires timers).
 *
 * One wave = one generation of events. A transition (a timer, or the handler of a delivered event) pastes the source's `exit[*]` sub-tables
 * (and its descendants'), the transition's `on[k]` sub-table and enters the target; every tick-0 `Emit` / `Cancel` / `End(instance)` row of
 * those (and of every state entered at its entry tick: the declared start child, `parallel` region starts) is the next generation. Entering a
 * top-level `final` state announces `end` (a leaf: nothing handles it, but it still occupies a wave); a child `final` carries its `Emit(<parent>.end)` row.
 *
 * The count is an upper bound: guards, windows and "is that level active" are ignored, so it never under-counts the player. A chain that
 * comes back to an event already on it cannot be bounded and is reported as a cycle.
 */
internal object SameTickDepth {
    /** [depth] is the table's `bounds.sameTickDepth`; [cycle] names an event on a same-tick cycle, else null. */
    class Result(val depth: Int, val cycle: String?)

    private const val FINAL_END = "<machine end>"

    fun of(states: List<StateTable>, subs: Map<EventPath, SubTable>): Result {
        val byPath = states.associateBy { it.state.value }
        val allBranches: List<Pair<StateTable, Branch>> = states.flatMap { s -> s.branches.map { s to it } } +
            subs.values.mapNotNull { sub -> byPath[sub.owner.value]?.let { owner -> sub.branches.map { owner to it } } }.flatten()

        fun eventsOf(rows: List<Row>): List<String> = rows.filter { it.tick == 0 }.mapNotNull { r ->
            when (val a = r.action) {
                is Action.Emit -> a.event.value
                is Action.Cancel -> "cancel"
                is Action.End -> if (a.instance.path.isEmpty()) null else a.instance.text + ".end"
                else -> null
            }
        }

        /** Events produced by entering [s] at one tick, including the states entered by its tick-0 `Enter` rows. */
        fun entryEvents(s: StateTable, seen: MutableSet<String> = HashSet()): List<String> {
            if (!seen.add(s.state.value)) return emptyList()
            val out = ArrayList(eventsOf(s.rows))
            if (s.isFinal && '/' !in s.state.value && !byPath.keys.any { it != s.state.value && s.state.value.startsWith("$it/") }) out += FINAL_END
            for (r in s.rows) {
                val a = r.action
                if (r.tick == 0 && a is Action.Enter && a.state.value != s.state.value) byPath[a.state.value]?.let { out += entryEvents(it, seen) }
            }
            return out
        }

        fun exitEvents(s: StateTable): List<String> = subs.values
            .filter { it.owner.value == s.state.value || it.owner.value.startsWith(s.state.value + "/") }
            .filter { it.event.value.contains(".exit[") }
            .flatMap { eventsOf(it.rows) }

        /** Events produced the moment [b] of [s] fires. */
        fun fired(s: StateTable, b: Branch): List<String> {
            val out = ArrayList<String>()
            if (b.target != null) out += exitEvents(s)
            b.subTable?.let { k -> subs[k]?.let { out += eventsOf(it.rows) } }
            b.target?.let { t -> byPath[t.value]?.let { out += entryEvents(it) } }
            return out
        }

        /** A branch of [owner] listens to [event] as written (`hit1.end`) or as the owner's own (`end` for `<owner>.end`). */
        fun listens(owner: StateTable, b: Branch, event: String): Boolean =
            b.event.value == event || event == owner.state.value.substringAfterLast('/') + "." + b.event.value

        /** [gone] = the state a transition leaves: it and its descendants are exited, so they no longer hear what the transition produces. */
        fun gone(owner: StateTable, gone: String?): Boolean =
            gone != null && (owner.state.value == gone || owner.state.value.startsWith("$gone/"))

        val memo = HashMap<String, Int>()
        val stack = ArrayList<String>()
        var cycle: String? = null

        fun depth(event: String, leaving: String?): Int {
            val key = event + "@" + (leaving ?: "")
            memo[key]?.let { return it }
            if (key in stack) { cycle = cycle ?: (stack.dropWhile { it != key } + key).joinToString(" -> "); return 1 }
            stack += key
            var onward = 0
            for ((s, b) in allBranches) {
                if (!listens(s, b, event) || gone(s, leaving)) continue
                val next = if (b.target != null) s.state.value else null
                for (e in fired(s, b)) onward = maxOf(onward, depth(e, next))
            }
            stack.removeAt(stack.size - 1)
            return (1 + onward).also { memo[key] = it }
        }

        var worst = 0
        // the start state's entry events are pending before the first tick: wave 1 delivers them
        states.firstOrNull()?.let { s -> for (e in entryEvents(s)) worst = maxOf(worst, depth(e, null) - 1) }
        // a branch fires in wave 1 (a timer, or an external event) or later; either way its products are delivered one wave after it
        for ((s, b) in allBranches) for (e in fired(s, b)) worst = maxOf(worst, depth(e, if (b.target != null) s.state.value else null))
        return Result(worst, cycle)
    }
}
