package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.skillengine.world.WorldBlockRules
import java.util.PriorityQueue

/**
 * Tick-driven ledger of temporary block edits. Every plain block a skill changes is restored after its revert
 * delay (there are no permanent skill edits); overlapping edits keep the very first original; [revertAll]
 * (match end, reload, shutdown) restores everything at once. Call [tick] once per server tick.
 */
public class SkillWorldEditor(public val world: ISkillWorldGateway) {

    private class Edited(val original: String?, var token: Long)

    private class Task(val due: Long, val seq: Long, val origin: EditOrigin?, val action: () -> Unit)

    /** Which cast / op queued a delayed edit, so a failing edit can be reported and its cast ended. */
    public class EditOrigin(public val cast: CastId, public val opId: String)

    /** Called once per delayed edit that threw; the editor has already dropped the rest of that cast's queued edits. */
    public fun interface FailureSink {
        public fun failed(origin: EditOrigin, cause: RuntimeException)
    }

    /** Set by the runner; an edit with an origin that throws is reported here, never swallowed. */
    public var failures: FailureSink? = null

    private var now: Long = 0
    private var seq: Long = 0
    private var nextToken: Long = 0
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.due }, { it.seq }))
    private val edited = HashMap<BlockCell, Edited>()

    public val pendingReverts: Int get() = edited.size

    public fun tick() {
        now++
        while (true) {
            val head = queue.peek() ?: return
            if (head.due > now) return
            queue.poll()
            try {
                head.action()
            } catch (e: RuntimeException) {
                val origin = head.origin ?: throw e
                // One cast's broken edit must not stall the others: drop what it still has queued, report once, go on.
                queue.removeIf { it.origin?.cast == origin.cast }
                val sink = failures ?: throw e
                sink.failed(origin, e)
            }
        }
    }

    /** Run [action] after [delayTicks] (at least 1) server ticks. */
    public fun later(delayTicks: Long, origin: EditOrigin? = null, action: () -> Unit) {
        queue.add(Task(now + delayTicks.coerceAtLeast(1), seq++, origin, action))
    }

    /**
     * Run [change] on [cell] and schedule its undo after [revertAfterTicks]. [change] returns false when it
     * changed nothing (then no revert is owed).
     */
    public fun edit(cell: BlockCell, revertAfterTicks: Long, change: () -> Boolean) {
        val fresh = edited[cell] == null
        val entry = edited[cell] ?: Edited(world.blockAt(cell), 0L).also { edited[cell] = it }
        val changed = try {
            change()
        } catch (e: RuntimeException) {
            if (fresh) edited.remove(cell)
            throw e
        }
        if (!changed) {
            if (fresh) edited.remove(cell)
            return
        }
        val token = ++nextToken
        entry.token = token
        later(revertAfterTicks) { if (edited[cell]?.token == token) restore(cell) }
    }

    public fun revertAll() {
        queue.clear()
        for (cell in edited.keys.toList()) restore(cell)
    }

    private fun restore(cell: BlockCell) {
        val original = (edited.remove(cell) ?: return).original
        if (WorldBlockRules.isAir(original)) world.remove(cell) else world.place(cell, original!!)
    }
}
