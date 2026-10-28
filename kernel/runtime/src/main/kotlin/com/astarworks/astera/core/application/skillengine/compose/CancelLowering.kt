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
    public fun subTableKey(state: StatePath, scopeText: String): EventPath = EventPath("${state.value}.cancel[$scopeText]")

    public fun lower(op: OpId, target: CancelTarget, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val self = InstanceId(lc.self.path + InstanceId.Segment(op, null))
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
                scopeText = id.text
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
        return InstanceId(if (target.fromRoot) segments else lc.self.path + segments)
    }
}
