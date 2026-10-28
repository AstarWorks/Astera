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

/** Result of [MachineTables.lower]; sub-tables are keyed by [EventPath] (11.3 `sub <state>.exit[<reason>]`). */
public data class MachineLowering(
    val states: List<StateTable>,
    val subTables: Map<EventPath, SubTable>,
    val instances: List<InstanceId>,
    val writes: Map<InstanceId, WriteStats>,
    val emitted: Set<EventPath>,
)

/**
 * ADR-0303 10 / 10.2 / 10.6: Machine -> one [StateTable] per state, one exit sub-table per reachable (state, exit.reason).
 *
 * Hierarchy is the `/` prefix of the state id (PartExpander already flattens `use:` that way); the nearest existing prefix state is the parent.
 * A transition of an ancestor A is bubbled into every descendant S as a branch placed after S's own branches (child first, 10.1). Its sub-table
 * runs S's exit (PREEMPT) and each intermediate state's exit innermost first, then A's exit with the transition's own reason, then the effects.
 * `exit.reason` guards on ops (`when: "= exit.reason != 'cancel'"`) are resolved here, per reason, so the row is simply absent (10.2 rev4.1).
 */
public object MachineTables {
    private val reasonTest = Regex("""^=\s*exit\.reason\s*(==|!=)\s*'(timer|event|preempt|cancel)'\s*$""")
    private val reasonIn = Regex("""^=\s*exit\.reason\s+in\s+\[([^\]]*)]\s*$""")

    public fun lower(skillId: String, machine: Machine, lowerer: SectionLowerer, config: EngineConfig): Result<MachineLowering, SkillDecodeError> {
        fun bad(path: String, msg: String) = Result.Failure(SkillDecodeError.Invalid(skillId, path, msg))
        if (machine.states.isEmpty()) return bad("machine", "machine has no states")
        if (machine.start !in machine.states) return bad("machine.start", "start state '${machine.start}' is not declared")

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
            l.subTables.forEach { subs[it.event] = it }
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

        /** Rows of one state's exit, reason resolved. */
        fun exitRows(st: MachineState, reason: ExitReason): Result<List<Row>, SkillDecodeError> {
            val ops = section(st, "exit", st.exit, reason)
            if (ops is Result.Failure) return ops
            return Result.Success(
                listOf(row(0, Action.Exit(StatePath(st.id), reason), "machine.${st.id}.exit")) + (ops as Result.Success).value,
            )
        }

        fun ancestors(id: String): List<MachineState> {
            val out = ArrayList<MachineState>()
            var cur = id
            while (cur.contains('/')) {
                cur = cur.substringBeforeLast('/')
                machine.states[cur]?.let { out += it }
            }
            return out
        }

        fun guard(t: MachineTransition): Guard? = t.whenExpr?.let { w ->
            if (w.startsWith("=")) Guard.Expr(w.removePrefix("=").trim()) else Guard.Named(w)
        }

        fun timerTicks(v: Any?): Int? = when (v) {
            null -> null
            is Number -> v.toInt()
            else -> null
        }

        for ((id, st) in machine.states) {
            val path = StatePath(id)
            val parents = ancestors(id)
            val where = "machine.states.$id"

            if (st.forTicks != null && timerTicks(st.forTicks) == null) {
                return bad("$where.for", "'for' must be a number of ticks known at compile time (C5), got '${st.forTicks}'")
            }
            val forTicks = timerTicks(st.forTicks)
            if (forTicks != null && forTicks < 0) return bad("$where.for", "'for' must be >= 0")
            if (forTicks != null && forTicks > config.maxCastTicks) return bad("$where.for", "'for' $forTicks exceeds maxCastTicks ${config.maxCastTicks}")
            if (st.then != null && st.then !in machine.states) return bad("$where.then", "unknown state '${st.then}'")

            val rows = ArrayList<Row>()
            rows += row(0, Action.Enter(path), "$where.enter")
            for ((sec, ops) in listOf("enter" to st.enter, "do" to st.doOps)) {
                when (val r = section(st, sec, ops, null)) {
                    is Result.Failure -> return r
                    is Result.Success -> rows += r.value
                }
            }
            if (st.final) {
                val parent = parents.firstOrNull()
                rows += if (parent == null) row(0, Action.End(root), "$where.final")
                else row(0, Action.Emit(EventPath(parent.id + ".end")), "$where.final").also { emitted += EventPath(parent.id + ".end") }
            }

            val branches = ArrayList<Branch>()

            /** Registers the sub-table of a transition fired from [st] (own) or bubbled from [owner]; returns its key. */
            fun transitionSub(owner: MachineState, k: Int, t: MachineTransition, timer: Boolean): Result<EventPath, SkillDecodeError> {
                val ownReason = if (timer) ExitReason.TIMER else ExitReason.EVENT
                val own = owner.id == id
                val key = when {
                    own && t.effects.isEmpty() -> "$id.exit[${ownReason.name.lowercase()}]"
                    own -> "$id.exit[${ownReason.name.lowercase()}]#$k"
                    else -> "$id.exit[preempt]/${owner.id}#$k"
                }
                val ev = EventPath(key)
                if (ev in subs) return Result.Success(ev)
                val body = ArrayList<Row>()
                if (own) {
                    when (val r = exitRows(st, ownReason)) { is Result.Failure -> return r; is Result.Success -> body += r.value }
                } else {
                    // innermost first: S, then each ancestor strictly below the owner, then the owner
                    val chain = listOf(st) + parents.takeWhile { it.id != owner.id }
                    for (c in chain) when (val r = exitRows(c, ExitReason.PREEMPT)) { is Result.Failure -> return r; is Result.Success -> body += r.value }
                    when (val r = exitRows(owner, ownReason)) { is Result.Failure -> return r; is Result.Success -> body += r.value }
                }
                if (t.effects.isNotEmpty()) {
                    val eff = lowerer.lower(StatePath(owner.id), "on[$k].effects", t.effects)
                    when (eff) { is Result.Failure -> return eff; is Result.Success -> { absorb(eff.value); body += eff.value.rows } }
                }
                subs[ev] = SubTable(ev, path, TickRange(0, 0), 1, body, emptyList())
                return Result.Success(ev)
            }

            fun collect(owner: MachineState, bubbled: Boolean): Result<Unit, SkillDecodeError> {
                val ownerFor = timerTicks(owner.forTicks)
                owner.on.forEachIndexed { k, t ->
                    if (t.to !in machine.states) return bad("machine.states.${owner.id}.on[$k].to", "unknown state '${t.to}'")
                    val timer = t.event == null
                    if (timer && bubbled) return@forEachIndexed // an ancestor timer is measured from the ancestor's entry; not statically placeable in a descendant (see report)
                    val after = if (timer) timerTicks(t.afterTicks) ?: return bad("machine.states.${owner.id}.on[$k].after", "'after' must be a compile-time tick count (C5)") else 0
                    val sub = when (val r = transitionSub(owner, k, t, timer)) { is Result.Failure -> return r; is Result.Success -> r.value }
                    branches += Branch(
                        event = EventPath(if (timer) "timer($after)" else t.event!!),
                        window = if (timer) TickRange(after, after) else TickRange(0, forTicks ?: config.maxCastTicks),
                        guard = guard(t), target = StatePath(t.to), subTable = sub,
                    )
                }
                val thenId = st.then
                if (!bubbled && thenId != null && ownerFor != null) {
                    val sub = when (val r = transitionSub(owner, -1, MachineTransition(null, ownerFor, null, thenId, emptyList()), true)) { is Result.Failure -> return r; is Result.Success -> r.value }
                    branches += Branch(EventPath("timer($ownerFor)"), TickRange(ownerFor, ownerFor), null, StatePath(thenId), sub)
                }
                return Result.Success(Unit)
            }

            when (val r = collect(st, false)) { is Result.Failure -> return r; is Result.Success -> Unit }
            for (a in parents) when (val r = collect(a, true)) { is Result.Failure -> return r; is Result.Success -> Unit }

            // cancel is an event whose sub-table is the exits depth first, then end (10.6)
            if (!st.final || parents.isNotEmpty()) {
                val key = EventPath("$id.exit[cancel]")
                val body = ArrayList<Row>()
                for (c in listOf(st) + parents) when (val r = exitRows(c, ExitReason.CANCEL)) { is Result.Failure -> return r; is Result.Success -> body += r.value }
                body += row(0, Action.End(root), "cancel")
                subs[key] = SubTable(key, path, TickRange(0, 0), 1, body, emptyList())
                branches += Branch(EventPath("cancel"), TickRange(0, config.maxCastTicks), null, null, key)
            }

            val hasTimer = branches.any { it.event.value.startsWith("timer(") }
            val duration: Duration = when {
                forTicks != null -> Duration.Fixed(forTicks)
                hasTimer -> Duration.Bounded(TickRange(branches.filter { it.event.value.startsWith("timer(") }.minOf { it.window.min }, config.maxCastTicks))
                else -> Duration.Bounded(TickRange(0, config.maxCastTicks))
            }
            states += StateTable(path, duration, st.then?.let(::StatePath), st.final, rows, branches)
        }
        return Result.Success(MachineLowering(states, subs, instances, writes, emitted))
    }

    /** Resolves an `exit.reason` guard on [op] for [reason]: null = drop the op, else the op with the guard removed. */
    internal fun resolveReason(op: OpMap, reason: ExitReason): OpMap? {
        val w = op["when"] as? String ?: return op
        val name = reason.name.lowercase()
        reasonTest.matchEntire(w.trim())?.let { m ->
            val eq = m.groupValues[1] == "=="
            return if ((m.groupValues[2] == name) == eq) op - "when" else null
        }
        reasonIn.matchEntire(w.trim())?.let { m ->
            val set = m.groupValues[1].split(',').map { it.trim().trim('\'') }
            return if (name in set) op - "when" else null
        }
        return op
    }
}
