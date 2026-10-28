package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.domain.skillengine.model.SkillEvents
import com.astarworks.astera.core.domain.skillengine.model.TimelineAt
import com.astarworks.astera.core.domain.skillengine.model.TimelineEntry
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

/**
 * The rows of one section's `timeline:`. A row fires at an absolute tick since cast start (`at: 4`) or
 * [TimelineAt.AfterEvent] ticks after an event was first raised (`at: release+2`); with `every` it repeats
 * until its `until` event has been raised or, with `for: N`, N ticks after its first firing (end exclusive: `every: 2, for: 10` fires 5 times). [due] is called once per cast tick and returns each row at most once
 * per tick, in declaration order.
 */
internal class Timeline<O : SkillOp>(private val rows: List<TimelineEntry<O>>) {
    private class State(var next: Int? = null, var done: Boolean = false, var first: Int? = null, val firedSources: MutableSet<String?> = HashSet())

    /** A due row and, for a one-shot `event+N` row, the raise it is anchored to (v1 scheduled the delayed mechanics per raise). */
    class Fire<O : SkillOp>(val row: TimelineEntry<O>, val event: CastEvent?)

    private val states = rows.map { State() }

    /**
     * @param since ticks since cast start (this tick)
     * @param eventSince ticks-since-start at which an event was first raised, or null
     * @param raised whether an event has been raised
     */
    fun due(since: Int, startTick: Int, raises: (String) -> List<CastEvent>, eventSince: (String) -> Int?, raised: (String) -> Boolean): List<Fire<O>> {
        val out = ArrayList<Fire<O>>()
        for ((i, row) in rows.withIndex()) {
            val st = states[i]
            if (st.done) continue
            val at0 = row.at
            if (at0 is TimelineAt.AfterEvent && (row.everyTicks == null || row.everyTicks!! <= 0) && row.untilEvent == null) {
                // One-shot `event+N`: one firing per raise (per source), each carrying its own raise as the current event.
                for (e in raises(at0.event)) {
                    if (e.source in st.firedSources) continue
                    if (since < e.tick - startTick + at0.offsetTicks) continue
                    st.firedSources += e.source
                    out += Fire(row, e)
                }
                continue
            }
            if (row.untilEvent != null && raised(row.untilEvent!!)) {
                st.done = true
                continue
            }
            if (st.next == null) {
                st.next = when (val at = row.at) {
                    is TimelineAt.Tick -> at.tick
                    is TimelineAt.AfterEvent -> eventSince(at.event)?.plus(at.offsetTicks)
                }
            }
            val n = st.next ?: continue
            if (since < n) continue
            out += Fire(row, null)
            if (st.first == null) st.first = n
            val every = row.everyTicks
            if (every == null || every <= 0) {
                st.done = true
            } else {
                var next = n + every
                while (next <= since) next += every
                val span = row.forTicks
                if (span != null && next >= st.first!! + span) st.done = true else st.next = next
            }
        }
        return out
    }

    /**
     * True while a row can still fire without any further event. An event-relative row counts once its event has been
     * raised (even in the tick the event was raised after this timeline's [due] ran, e.g. `hit` from the swept resolve),
     * never before.
     */
    fun pending(eventSince: (String) -> Int?, raises: (String) -> List<CastEvent>): Boolean = rows.indices.any { i ->
        val a = rows[i].at
        if (a is TimelineAt.AfterEvent && (rows[i].everyTicks == null || rows[i].everyTicks!! <= 0) && rows[i].untilEvent == null) {
            return@any raises(a.event).any { it.source !in states[i].firedSources }
        }
        // A row that repeats `until: end` is released by the cast ending, so it must not be what keeps the cast alive.
        val at = rows[i].at
        !states[i].done && rows[i].untilEvent != SkillEvents.END &&
            (states[i].next != null || at is TimelineAt.Tick || (at is TimelineAt.AfterEvent && eventSince(at.event) != null))
    }
}
