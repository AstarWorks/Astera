package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.MachineTransition
import com.astarworks.astera.core.domain.skillengine.part.OpMap
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.Guard
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.WriteStats

/** Lowers the ops of one machine section (`enter` / `do` / `exit` / transition effects) to rows at tick 0 of that section. */
public fun interface SectionLowerer {
    public fun lower(state: StatePath, section: String, ops: List<OpMap>): Result<Lowered, SkillDecodeError>
}

/** Result of [MachineTables.lower]; sub-tables are keyed by [EventPath] (11.3 `sub <state>.exit[<reason>]` / `sub <state>.on[<k>]`). */
public data class MachineLowering(
    val states: List<StateTable>,
    val subTables: Map<EventPath, SubTable>,
    val instances: List<InstanceId>,
    val writes: Map<InstanceId, WriteStats>,
    val emitted: Set<EventPath>,
    /** R45: the `machine.world` rows, owned by the machine root (ticks from the cast start). */
    val world: List<Row> = emptyList(),
)

/**
 * ADR-0303 10 / 10.2 (rev4.2) / 14: Machine -> one [StateTable] per state plus exactly two kinds of sub-table:
 * `<state>.exit[<reason>]` (one per reachable reason, first row `Exit(state, reason)`) and `<state>.on[<k>]` (the `do:` effects of the k-th
 * `on:` entry, absent when empty). There is no static bubbling: every branch belongs to the level that declares it, and the player pastes
 * `D.exit[preempt]` for the active descendants, then `L.exit[R]`, then `L.on[k]`, then enters the target. Ancestor timers are never copied
 * into descendants. Cancel has no table of its own: `exit[cancel]` is the cancel exit, the scopes are played by the player (10.6).
 *
 * Hierarchy is the `/` prefix of the state id (PartExpander flattens `use:` that way); the nearest existing prefix state is the parent. A
 * parent's rows enter its direct children at the entry tick: every `parallel` region start, else the state's declared `start` child (R36, required
 * whenever it has children: `NestedStartMissing`; `machine.start` naming a child is `StartNamesChild`). `parallel` regions are flattened into `<parent>/<region state>`; the parent emits
 * `<parent>.end` through the player when every region is final.
 * `exit.reason` guards on ops (`when: "= exit.reason != 'cancel'"`) are resolved here, per reason, so the row is simply absent (10.2 rev4.1).
 * Every section is lowered at tick 0 of its own base: `enter` / `do` rows are relative to the state base (a `lock` row's `AtTick(n)` therefore
 * counts from the state entry, item 14.3), `exit` / `on[k]` sub-table rows relative to the firing tick.
 */
public object MachineTables {
    private val reasonTest = Regex("""^=\s*exit\.reason\s*(==|!=)\s*'(timer|event|preempt|cancel)'\s*$""")
    private val reasonIn = Regex("""^=\s*exit\.reason\s+in\s+\[([^\]]*)]\s*$""")

    private class Flat(val states: LinkedHashMap<String, MachineState>, val regionStarts: MutableMap<String, MutableList<String>>)

    /** Renames a region machine's states under [prefix] and recurses into its own regions; returns an error message or null. */
    private fun flatten(m: Machine, prefix: String, into: Flat): String? {
        for ((_, s) in m.states) {
            val id = prefix + s.id
            if (id in into.states) return "state '$id' is declared twice"
            into.states[id] = s.copy(
                id = id,
                then = s.then?.let { prefix + it },
                on = s.on.map { it.copy(to = prefix + it.to) },
                parallel = emptyList(),
            )
        }
        for ((_, s) in m.states) {
            val id = prefix + s.id
            for (r in s.parallel) {
                if (r.states.isEmpty()) return "parallel region of '$id' has no states"
                if (r.start !in r.states) return "parallel region start '${r.start}' of '$id' is not declared"
                flatten(r, "$id/", into)?.let { return it }
                into.regionStarts.getOrPut(id) { ArrayList() } += "$id/${r.start}"
            }
        }
        return null
    }

    public fun lower(skillId: String, machine: Machine, lowerer: SectionLowerer, config: EngineConfig): Result<MachineLowering, SkillDecodeError> {
        fun bad(path: String, msg: String) = Result.Failure(SkillDecodeError.Invalid(skillId, path, msg))
        if (machine.states.isEmpty()) return bad("machine", "machine has no states")
        // an authored `start` naming a child is StartNamesChild at decode; after part expansion a calling start state is a flattened path
        if (machine.start !in machine.states) return bad("machine.start", "start state '${machine.start}' is not declared")

        val flat = Flat(LinkedHashMap(), LinkedHashMap())
        flatten(machine, "", flat)?.let { return bad("machine.states", it) }
        val all = flat.states

        val states = ArrayList<StateTable>()
        val subs = LinkedHashMap<EventPath, SubTable>()
        val instances = ArrayList<InstanceId>()
        val writes = LinkedHashMap<InstanceId, WriteStats>()
        val emitted = LinkedHashSet<EventPath>()
        val root = InstanceId(emptyList())

        fun absorb(l: Lowered) {
            instances += l.instances
            writes += l.writes
            emitted += l.events
            // occurrences of one repeating op share an event path and identical rows; their firing windows union
            l.subTables.forEach { t ->
                val prev = subs[t.event]
                subs[t.event] = if (prev == null) t else t.copy(
                    window = TickRange(minOf(prev.window.min, t.window.min), maxOf(prev.window.max, t.window.max)),
                    maxFirings = maxOf(prev.maxFirings, t.maxFirings),
                )
            }
        }

        fun section(st: MachineState, name: String, ops: List<OpMap>, reason: ExitReason?): Result<List<Row>, SkillDecodeError> {
            val live = if (reason == null) ops else ops.mapNotNull { resolveReason(it, reason) }
            if (live.isEmpty()) return Result.Success(emptyList())
            return when (val r = lowerer.lower(StatePath(st.id), name, live)) {
                is Result.Failure -> r
                is Result.Success -> { absorb(r.value); Result.Success(r.value.rows) }
            }
        }

        fun row(tick: Int, action: Action, src: String) = Row(tick, root, action, SrcPath(src))

        fun parentOf(id: String): MachineState? {
            var cur = id
            while (cur.contains('/')) {
                cur = cur.substringBeforeLast('/')
                all[cur]?.let { return it }
            }
            return null
        }

        fun guard(t: MachineTransition): Guard? = t.whenExpr?.let { w ->
            if (w.startsWith("=")) Guard.Expr(w.removePrefix("=").trim()) else Guard.Named(w)
        }

        fun timerTicks(v: Any?): Int? = when (v) {
            null -> null
            is Number -> v.toInt()
            else -> null
        }

        /** The children [id] enters at its entry tick: every `parallel` region start, else its declared `start` (R36). */
        fun childStarts(id: String): List<String> {
            flat.regionStarts[id]?.let { return it }
            val start = all.getValue(id).start ?: return emptyList()
            return listOf("$id/$start")
        }

        // R45: the cast-level `world:` rows are lowered once, at the cast's tick 0, as a machine-root section (ADR-0303 10.1): the start state
        // supplies only the anchor scope; the rows are not part of any state's table, so a state exit never ends them.
        var worldRows: List<Row> = emptyList()
        if (machine.world.isNotEmpty()) {
            when (val r = section(all.getValue(machine.start), "world", machine.world, null)) {
                is Result.Failure -> return r
                is Result.Success -> worldRows = r.value
            }
        }

        for ((id, st) in all) {
            val hasKids = all.keys.any { it != id && parentOf(it)?.id == id }
            if (hasKids && st.start == null && id !in flat.regionStarts) return Result.Failure(SkillDecodeError.NestedStartMissing(skillId, id))
            if (st.start != null && "$id/${st.start}" !in all) return bad("machine.states.$id.start", "start '${st.start}' is not a child state of '$id'")
        }

        for ((id, st) in all) {
            val path = StatePath(id)
            val parent = parentOf(id)
            val where = "machine.states.$id"

            if (st.forTicks != null && timerTicks(st.forTicks) == null) {
                return Result.Failure(SkillDecodeError.RuntimeTiming("$where.for", "for", "'for' must be a number of ticks known at compile time (C5), got '${st.forTicks}'; write until: / range: instead", skillId))
            }
            val forTicks = timerTicks(st.forTicks)
            if (forTicks != null && forTicks < 0) return bad("$where.for", "'for' must be >= 0")
            if (forTicks != null && forTicks > config.maxCastTicks) return bad("$where.for", "'for' $forTicks exceeds maxCastTicks ${config.maxCastTicks}")
            if (st.then != null && st.then !in all) return bad("$where.then", "unknown state '${st.then}'")

            val rows = ArrayList<Row>()
            rows += row(0, Action.Enter(path), "$where.enter")
            for ((sec, ops) in listOf("enter" to st.enter, "do" to st.doOps)) {
                when (val r = section(st, sec, ops, null)) {
                    is Result.Failure -> return r
                    is Result.Success -> rows += r.value
                }
            }
            for (child in childStarts(id)) rows += row(0, Action.Enter(StatePath(child)), "$where.child")
            if (st.final) {
                rows += if (parent == null) row(0, Action.End(root), "$where.final")
                else row(0, Action.Emit(EventPath(parent.id + ".end")), "$where.final").also { emitted += EventPath(parent.id + ".end") }
            }

            val branches = ArrayList<Branch>()
            var hasTimer = false
            var hasEvent = false
            st.on.forEachIndexed { k, t ->
                if (t.to !in all) return bad("$where.on[$k].to", "unknown state '${t.to}'")
                val timer = t.event == null
                val after = if (timer) timerTicks(t.afterTicks) ?: return bad("$where.on[$k].after", "'after' must be a compile-time tick count (C5)") else 0
                if (timer) hasTimer = true else hasEvent = true
                var sub: EventPath? = null
                if (t.effects.isNotEmpty()) {
                    when (val r = lowerer.lower(path, "on[$k].effects", t.effects)) {
                        is Result.Failure -> return r
                        is Result.Success -> {
                            absorb(r.value)
                            if (r.value.rows.isNotEmpty()) {
                                val key = EventPath("$id.on[$k]")
                                subs[key] = SubTable(key, path, TickRange(0, 0), 1, r.value.rows, emptyList())
                                sub = key
                            }
                        }
                    }
                }
                branches += Branch(
                    event = EventPath(if (timer) "timer($after)" else t.event!!),
                    window = if (timer) TickRange(after, after) else TickRange(0, forTicks ?: config.maxCastTicks),
                    guard = guard(t), target = StatePath(t.to), subTable = sub,
                )
            }
            val thenId = st.then
            if (thenId != null && forTicks != null) {
                hasTimer = true
                branches += Branch(EventPath("timer($forTicks)"), TickRange(forTicks, forTicks), null, StatePath(thenId), null)
            }

            // one exit sub-table per reason that can reach this level (10.2); the player pastes it, then `on[k]`
            val reasons = buildList {
                if (hasTimer) add(ExitReason.TIMER)
                if (hasEvent) add(ExitReason.EVENT)
                if (parent != null) add(ExitReason.PREEMPT)
                if (!(st.final && parent == null && !hasTimer && !hasEvent)) add(ExitReason.CANCEL)
            }
            for (reason in reasons) {
                val ops = section(st, "exit", st.exit, reason)
                if (ops is Result.Failure) return ops
                val key = EventPath("$id.exit[${reason.name.lowercase()}]")
                val body = listOf(row(0, Action.Exit(path, reason), "$where.exit")) + (ops as Result.Success).value
                subs[key] = SubTable(key, path, TickRange(0, 0), 1, body, emptyList())
            }

            val timers = branches.filter { it.event.value.startsWith("timer(") }
            // 10.1: entering a `final` state ends the machine (a child final announces `<parent>.end`), so a final lasts 0 ticks on every path
            val duration: Duration = when {
                st.final -> Duration.Fixed(0)
                forTicks != null -> Duration.Fixed(forTicks)
                // no event can hold the state and a timer is unguarded (a folded part continuation is `after: 0`): it leaves by the earliest such timer
                !hasEvent && timers.any { it.guard == null } ->
                    Duration.Bounded(TickRange(timers.minOf { it.window.min }, timers.filter { it.guard == null }.minOf { it.window.min }))
                timers.isNotEmpty() -> Duration.Bounded(TickRange(timers.minOf { it.window.min }, config.maxCastTicks))
                else -> Duration.Bounded(TickRange(0, config.maxCastTicks))
            }
            states += StateTable(path, duration, st.then?.let(::StatePath), st.final, rows, branches)
        }
        // the table has no start field: the first state is the entry (StaticChecks reachability, the player). A part call as `machine.start` is
        // expanded to its child entry path (`guard/parry_window`), which may be declared after other states, so the entry is put first (stable).
        val entryFirst = states.sortedBy { it.state.value != machine.start }
        return Result.Success(MachineLowering(entryFirst, subs, instances, writes, emitted, worldRows))
    }

    /**
     * Resolves `exit.reason` guards on [op] for [reason]: null = drop the op, else the op with those guards removed. A guard may be one conjunct
     * of a top-level `&&` chain (example e: `exit.reason != 'cancel' && dist(...) < range`); the other conjuncts stay as the runtime `when` (10.2).
     */
    internal fun resolveReason(op: OpMap, reason: ExitReason): OpMap? {
        val w = op["when"] as? String ?: return op
        val name = reason.name.lowercase()
        val parts = w.trim().removePrefix("=").trim().split("&&").map { it.trim() }
        val kept = ArrayList<String>()
        for (part in parts) {
            val c = "= $part"
            val test = reasonTest.matchEntire(c)
            val inn = reasonIn.matchEntire(c)
            when {
                test != null -> if ((test.groupValues[2] == name) != (test.groupValues[1] == "==")) return null
                inn != null -> if (name !in inn.groupValues[1].split(',').map { it.trim().trim('\'') }) return null
                else -> kept += part
            }
        }
        return when {
            kept.size == parts.size -> op
            kept.isEmpty() -> op - "when"
            else -> op + ("when" to ("= " + kept.joinToString(" && ")))
        }
    }
}
