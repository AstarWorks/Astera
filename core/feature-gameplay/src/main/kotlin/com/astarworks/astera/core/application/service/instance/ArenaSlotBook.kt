package com.astarworks.astera.core.application.service.instance

import com.astarworks.astera.core.domain.stage.StageId
import java.util.concurrent.ConcurrentHashMap

/**
 * Bookkeeping of the built arena copies ("builds") that sit on [SlotAllocator] slots, kept free of Minecraft so the
 * lifecycle rules can be driven by a simulator. All mutations run on Global; reads are thread safe.
 *
 * Rules it enforces (each one was a way a slot or a map got lost):
 *  - a repair is claimed once ([beginRepair]); a duplicate / stale repair request for the same arena is ignored
 *    instead of freeing a slot that has meanwhile been handed to a NEW build (two arenas pasted into one slot);
 *  - a pristine warm copy (Ready) is never torn down by a repair request;
 *  - a slot is freed only while it is still the one this build owns ([finishRepair]);
 *  - the consecutive-failure budget is per arena key and is reset by every success, so five old hiccups do not
 *    permanently disable repairs.
 */
public class ArenaSlotBook<B : Any>(private val slots: SlotAllocator, private val maxFailures: Int = 5) {
    public data class Entry<B>(val slot: SlotAllocator.Slot, val build: B)

    private val builds = ConcurrentHashMap<StageId, Entry<B>>()
    private val repairing = ConcurrentHashMap.newKeySet<StageId>()
    private val failures = ConcurrentHashMap<StageId, Int>()

    public fun entries(): Collection<Entry<B>> = builds.values
    public fun build(id: StageId): Entry<B>? = builds[id]
    public fun contains(id: StageId): Boolean = builds.containsKey(id)

    /** A completed, unclaimed build of [template] (slot Ready and a build recorded for exactly that slot). */
    public fun ready(template: StageId): Entry<B>? {
        val slot = slots.snapshot().firstOrNull { it.stage == template && it.state == SlotAllocator.State.Ready } ?: return null
        return builds.values.firstOrNull { it.slot.index == slot.index }
    }

    /** Number of slots of [template] that are or will soon be claimable. */
    public fun waiting(template: StageId): Int = slots.snapshot().count {
        it.stage == template && (it.state == SlotAllocator.State.Ready || it.state == SlotAllocator.State.Building)
    }

    public fun claim(entry: Entry<B>) { slots.state(entry.slot.index, SlotAllocator.State.Running) }

    /** The paste completed: record the build and make the slot claimable. A success clears the failure budget. */
    public fun publish(id: StageId, entry: Entry<B>, failureKey: StageId = id) {
        builds[id] = entry
        slots.state(entry.slot.index, SlotAllocator.State.Ready)
        failures.remove(failureKey)
    }

    /** A paste/plan that never produced a build: give the slot back (only if it is still this build's slot). */
    public fun abandon(slot: SlotAllocator.Slot) {
        if (slots.snapshot().any { it.index == slot.index && it.stage == slot.stage && it.state == SlotAllocator.State.Building }) {
            slots.free(slot.index)
        }
    }

    /** Marks the slot of a failed paste Resetting (quarantined, never reused dirty). */
    public fun quarantine(slot: SlotAllocator.Slot) { slots.state(slot.index, SlotAllocator.State.Resetting) }

    /** Frees a quarantined slot after its rollback; returns false when the slot is no longer this one's. */
    public fun release(slot: SlotAllocator.Slot): Boolean {
        val current = slots.snapshot().firstOrNull { it.index == slot.index } ?: return false
        if (current.stage != slot.stage || current.state != SlotAllocator.State.Resetting) return false
        slots.free(slot.index)
        return true
    }

    /**
     * Claims the repair of the (ended) arena [id]. Returns `null` when there is nothing to repair, a repair is
     * already running, or the copy is a pristine Ready one nobody played on.
     */
    public fun beginRepair(id: StageId): Entry<B>? {
        val entry = builds[id] ?: return null
        val state = slots.snapshot().firstOrNull { it.index == entry.slot.index }?.state ?: return null
        if (state == SlotAllocator.State.Ready || state == SlotAllocator.State.Building) return null
        if (!repairing.add(id)) return null
        slots.state(entry.slot.index, SlotAllocator.State.Resetting)
        return entry
    }

    /** The repair rolled the slot back to the void: drop the build and free the slot (if it is still ours). */
    public fun finishRepair(id: StageId, entry: Entry<B>) {
        repairing.remove(id)
        if (builds[id] == entry) builds.remove(id)
        release(entry.slot)
        failures.remove(id)
    }

    /** The rollback failed and the plan was pasted again over the slot: it is claimable again. */
    public fun repasted(id: StageId, entry: Entry<B>, build: B) {
        repairing.remove(id)
        builds[id] = entry.copy(build = build)
        slots.state(entry.slot.index, SlotAllocator.State.Ready)
        failures.remove(id)
    }

    /** The repair attempt failed; releases the repair claim. Returns true while another attempt is allowed. */
    public fun repairFailed(id: StageId): Boolean {
        repairing.remove(id)
        return failures.merge(id, 1, Int::plus)!! <= maxFailures
    }

    /** A warm-copy build failed for [template]; returns true while a rebuild is allowed. */
    public fun buildFailed(template: StageId): Boolean = failures.merge(template, 1, Int::plus)!! <= maxFailures
}
