package com.astarworks.astera.core.application.skillengine.runtime.world

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillBlockAccess
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.skillengine.world.WorldBlockRules
import com.astarworks.astera.core.domain.world.WorldId
import java.util.PriorityQueue

/**
 * Tick-driven ledger of temporary block edits. Every plain block a skill changes is restored after its revert
 * delay (there are no permanent skill edits); overlapping edits keep the very first original and belong to the
 * cast that touched the cell last. [revertCast] restores only that cast's cells and drops its pending ripple
 * (v2 `BreakAreaExecutor` cancel); [revertAll] (match end, reload, shutdown) restores everything at once. Call [tick] once per server tick.
 */
public class SkillWorldEditor(public val blocks: ISkillBlockAccess) {

    private class Edited(val original: String?, var token: Long, var owner: CastRef)

    private class Task(val due: Long, val seq: Long, val castId: Long, val action: () -> Unit)

    private data class Key(val world: WorldId, val cell: BlockCell)

    private var now: Long = 0
    private var seq: Long = 0
    private var nextToken: Long = 0
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.due }, { it.seq }))
    private val edited = HashMap<Key, Edited>()

    public val pendingReverts: Int get() = edited.size

    public fun tick() {
        now++
        while (true) {
            val head = queue.peek() ?: return
            if (head.due > now) return
            queue.poll()
            head.action()
        }
    }

    /** Run [action] after [delayTicks] (at least 1) server ticks. */
    public fun later(cast: CastRef, delayTicks: Long, action: () -> Unit) {
        queue.add(Task(now + delayTicks.coerceAtLeast(1), seq++, cast.castId, action))
    }

    /**
     * Run [change] on [cell] and schedule its undo after [revertAfterTicks]. [change] returns false when it
     * changed nothing (then no revert is owed).
     */
    public fun edit(cast: CastRef, cell: BlockCell, revertAfterTicks: Long, change: () -> Boolean) {
        val key = Key(cast.world, cell)
        val fresh = edited[key] == null
        val entry = edited[key] ?: Edited(blocks.blockAt(cast, cell), 0L, cast).also { edited[key] = it }
        if (!change()) {
            if (fresh) edited.remove(key)
            return
        }
        val token = ++nextToken
        entry.token = token
        entry.owner = cast
        later(cast, revertAfterTicks) { if (edited[key]?.token == token) restore(key) }
    }

    /** Restore the cells [cast] edited last and drop its queued ripple / revert tasks; other casts' edits stay. */
    public fun revertCast(cast: CastRef) {
        queue.removeIf { it.castId == cast.castId }
        for (key in edited.filterValues { it.owner.castId == cast.castId }.keys) restore(key)
    }

    public fun revertAll() {
        queue.clear()
        for (key in edited.keys.toList()) restore(key)
    }

    private fun restore(key: Key) {
        val entry = edited.remove(key) ?: return
        if (WorldBlockRules.isAir(entry.original)) blocks.remove(entry.owner, key.cell) else blocks.place(entry.owner, key.cell, entry.original!!)
    }
}
