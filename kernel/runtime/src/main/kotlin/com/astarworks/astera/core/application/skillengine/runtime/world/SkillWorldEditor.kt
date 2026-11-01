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
 * (the break-area cancel); [revertAll] (match end, reload) restores everything now (bodies get a grace); [revertAllNow] (shutdown) does so unconditionally.
 * Call [tick] once per server tick.
 *
 * A cell is never restored into a living body: when [ISkillBlockAccess.isBodyIn] says an entity's hitbox overlaps it, that
 * cell is deferred (the edit stays owed) and retried every tick until it is free; the other cells restore normally.
 * Waiting is unbounded while a match runs. After [revertAll] (match end) the wait is capped at [END_GRACE_TICKS], then the
 * cell is restored regardless (the match's players are leaving the arena; cover is rebuilt by the match anyway).
 */
public class SkillWorldEditor(public val blocks: ISkillBlockAccess) {

    private class Edited(val original: String?, var token: Long, var owner: CastRef) {
        /** Set once the cell is waiting for its body to leave: the tick after which a match-end wait is over (Long.MAX = no cap yet). */
        var forceAt: Long = Long.MAX_VALUE
    }

    private class Task(val due: Long, val seq: Long, val castId: Long, val action: () -> Unit)

    private data class Key(val world: WorldId, val cell: BlockCell)

    private var now: Long = 0
    private var seq: Long = 0
    private var nextToken: Long = 0
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.due }, { it.seq }))
    private val edited = HashMap<Key, Edited>()

    /** Cells whose restore is waiting for a body to step out of them. */
    private val deferred = LinkedHashSet<Key>()

    public val pendingReverts: Int get() = edited.size

    /** Cells currently held back because a living body stands in them. */
    public val deferredReverts: Int get() = deferred.size

    public fun tick() {
        now++
        while (true) {
            val head = queue.peek() ?: break
            if (head.due > now) break
            queue.poll()
            head.action()
        }
        retryDeferred()
    }

    private fun retryDeferred() {
        if (deferred.isEmpty()) return
        for (key in deferred.toList()) {
            val entry = edited[key]
            if (entry == null) { deferred.remove(key); continue }
            restore(key, force = now >= entry.forceAt)
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
        // A fresh edit owns the cell again: its own revert is due later, so a restore that was waiting for a body is off.
        deferred.remove(key)
        entry.forceAt = Long.MAX_VALUE
        later(cast, revertAfterTicks) { if (edited[key]?.token == token) restore(key) }
    }

    /** Restore the cells [cast] edited last and drop its queued ripple / revert tasks; other casts' edits stay. */
    public fun revertCast(cast: CastRef) {
        queue.removeIf { it.castId == cast.castId }
        for (key in edited.filterValues { it.owner.castId == cast.castId }.keys) restore(key)
    }

    /** Match end / reload: restore every free cell now; a cell with a body in it keeps waiting for at most [END_GRACE_TICKS]. */
    public fun revertAll() {
        queue.clear()
        for ((key, entry) in edited.entries.toList()) {
            entry.forceAt = minOf(entry.forceAt, now + END_GRACE_TICKS)
            restore(key)
        }
    }

    /** Shutdown: nothing ticks afterwards, so restore every cell now, bodies or not. */
    public fun revertAllNow() {
        queue.clear()
        for (key in edited.keys.toList()) restore(key, force = true)
    }

    private fun restore(key: Key, force: Boolean = false) {
        val entry = edited[key] ?: return
        if (!force && blocks.isBodyIn(entry.owner, key.cell)) {
            deferred += key
            return
        }
        edited.remove(key)
        deferred.remove(key)
        if (WorldBlockRules.isAir(entry.original)) blocks.remove(entry.owner, key.cell) else blocks.place(entry.owner, key.cell, entry.original!!)
    }

    private companion object {
        /** Ticks a match-end restore waits for a body to leave before it restores anyway (10 s). */
        const val END_GRACE_TICKS: Long = 200
    }
}
