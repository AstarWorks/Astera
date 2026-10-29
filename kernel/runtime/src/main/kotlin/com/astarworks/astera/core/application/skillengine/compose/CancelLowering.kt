package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.ops.control.CancelTarget
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange

/**
 * `cancel` lowering (ADR-0303 10.6). One path for every cancel: a `Cancel` row at the op's tick, and one compiled sub-table per
 * (owner state, scope) holding the cancel's rows at offset 0. There is no second cleanup path: exit actions come from the
 * `exit[cancel]` sub-table of each state (W1-16, 10.2 `exit.reason`), the player runs them, then these rows.
 *
 * Sub-table key: [subTableKey] = `<state>.cancel[<scope>]` with scope `cast`, `state` or the instance text.
 */
public object CancelLowering {
    /** The key names the owner state once already, so the instance text drops its `<state>/` qualifier. */
    private fun keyText(id: InstanceId, state: StatePath): String = id.text.removePrefix(state.value + "/")

    public fun subTableKey(state: StatePath, scopeText: String): EventPath = EventPath("${state.value}.cancel[$scopeText]")

    public fun lower(op: OpId, target: CancelTarget, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val self = lc.self // the unroller already appended this op's own segment (item 5)
        val cancelEvent = EventPath("cancel")
        val endEvent = EventPath("end")
        fun row(action: Action) = Row(0, self, action, lc.src)

        val scope: CancelScope
        val scopeText: String
        val rows: List<Row>
        val produced: List<EventPath>
        when (target) {
            CancelTarget.Cast -> {
                scope = CancelScope.Cast
                scopeText = "cast"
                // Every state level from the one holding the op outward, innermost first; levels below it that are active at run
                // time (nested machines, parallel regions) are exited first by the player's preemption rule (10.2).
                val exits = stateChain(lc.state).map { row(Action.Exit(it, ExitReason.CANCEL)) }
                rows = exits + row(Action.Emit(cancelEvent)) + row(Action.Emit(endEvent))
                produced = listOf(cancelEvent, endEvent)
            }
            CancelTarget.State -> {
                scope = CancelScope.State
                scopeText = "state"
                // The event is offered to the state's `on:` first; the machine then takes `on: cancel` or leaves as by a timer,
                // with exit.reason = cancel (W1-16 owns the transition, so no Exit row here: it would run twice).
                rows = listOf(row(Action.Emit(cancelEvent)))
                produced = listOf(cancelEvent)
            }
            is CancelTarget.Instance -> {
                val id = resolve(target, lc)
                scope = CancelScope.Instance(id)
                scopeText = keyText(id, lc.state)
                // The instance and everything it owns end; a projectile body then fires `end` (never `hit`) from its own End row.
                rows = listOf(row(Action.End(id)))
                produced = emptyList()
            }
        }

        val key = subTableKey(lc.state, scopeText)
        val sub = SubTable(key, lc.state, TickRange(lc.clockOffset, lc.clockOffset), 1, rows, emptyList())
        return Result.Success(Lowered(rows = listOf(row(Action.Cancel(scope))), subTables = listOf(sub), events = produced))
    }

    /** `a/b/c` -> [a/b/c, a/b, a]: the state and its enclosing states, innermost first. */
    private fun stateChain(state: StatePath): List<StatePath> {
        val parts = state.value.split('/')
        return parts.indices.reversed().map { StatePath(parts.take(it + 1).joinToString("/")) }
    }

    private fun resolve(target: CancelTarget.Instance, lc: LowerContext): InstanceId {
        val segments = target.path.map { InstanceId.Segment(OpId(it.op), it.index) }
        // relative to the enclosing occurrence, the op's own segment is last; at the state's top level the first segment carries the state (`<state>/<op>`)
        val prefix = if (target.fromRoot) emptyList() else lc.self.path.dropLast(1)
        return if (prefix.isEmpty()) qualified(segments, lc.state) else InstanceId(prefix + segments)
    }

    /** The first segment of an instance id is the state-qualified op id (`cast/marker`); [segments] are written bare. */
    private fun qualified(segments: List<InstanceId.Segment>, state: StatePath): InstanceId =
        InstanceId(listOf(segments[0].copy(op = OpId(state.value + "/" + segments[0].op.value))) + segments.drop(1))

    /**
     * `cancel: <id>` is written relative to the enclosing group, then outward to the state's own level (10.6 `<id>`, the same lexical
     * lookup an `anchor:` name takes). [resolve] can only guess the innermost spelling, because a target written later or outside the
     * group is not known yet; once the state's section is lowered, [known] holds what it spawned and a cancel whose guess names nothing
     * is retargeted to the nearest enclosing spelling that does. A guess that names a spawned instance is left alone.
     */
    public fun retarget(lowered: Lowered, known: Collection<InstanceId>): Lowered {
        fun exists(id: InstanceId) = known.any { covers(id, it) }
        val moved = HashMap<InstanceId, InstanceId>()
        for (r in lowered.rows) {
            val scope = (r.action as? Action.Cancel)?.scope as? CancelScope.Instance ?: continue
            val id = scope.id
            if (exists(id)) continue
            val own = r.instance.path.dropLast(1)
            val tail = id.path.drop(own.size).takeIf { id.path.take(own.size) == own } ?: continue
            for (k in own.size - 1 downTo 0) {
                val candidate = if (k == 0) qualified(tail, StatePath(own[0].op.value.substringBeforeLast('/'))) else InstanceId(own.take(k) + tail)
                if (exists(candidate)) { moved[id] = candidate; break }
            }
        }
        if (moved.isEmpty()) return lowered
        val rows = lowered.rows.map { r ->
            val scope = (r.action as? Action.Cancel)?.scope as? CancelScope.Instance
            val to = scope?.let { moved[it.id] }
            if (to != null) r.copy(action = Action.Cancel(CancelScope.Instance(to))) else r
        }
        val subs = lowered.subTables.map { sub ->
            val from = moved.keys.firstOrNull { sub.event == subTableKey(sub.owner, keyText(it, sub.owner)) } ?: return@map sub
            val to = moved.getValue(from)
            sub.copy(
                event = subTableKey(sub.owner, keyText(to, sub.owner)),
                rows = sub.rows.map { r -> if ((r.action as? Action.End)?.instance == from) r.copy(action = Action.End(to)) else r },
            )
        }
        return lowered.copy(rows = rows, subTables = subs)
    }

    /** Segment-wise prefix match (StaticChecks check 4): an index-less target segment matches any index. */
    private fun covers(target: InstanceId, spawned: InstanceId): Boolean {
        if (target.path.size > spawned.path.size) return false
        for (i in target.path.indices) {
            val t = target.path[i]
            val sp = spawned.path[i]
            if (t.op != sp.op) return false
            if (t.index != null && t.index != sp.index) return false
        }
        return true
    }
}
