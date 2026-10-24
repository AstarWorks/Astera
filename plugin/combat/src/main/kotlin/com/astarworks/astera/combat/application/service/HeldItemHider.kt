package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.HeldItemAudience
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * `hide_held_item` runtime. A rig that draws the same weapon (VfxPlayer) must replace the real one with no
 * visible gap or overlap, so the hide is not sent when the step runs: if a rig [stage]s itself in the same tick
 * the hide waits for [reveal], which fires in the tick the rig's displays are made visible. The displays were spawned a
 * tick earlier (hidden by view range 0), so the client already has them ticked. Without a rig the hide is sent at the end
 * of the step's own tick (gateway: server tick end).
 *
 * Two audiences are timed apart while a rig is staged. Other viewers see the rig's first/last pose equal the third-person
 * held pose, so for them the hide follows [reveal] one full tick later and the restore is sent at [rigEnded] (a full tick
 * before the rig's displays are removed): a one-tick overlap reads as one weapon. The owner's own client draws the held item
 * with the first-person hand model, nowhere near the rig, so any overlap shows two swords: the owner is hidden in the very tick
 * of [reveal] and restored exactly when the rig is removed ([rigRemoved]). Without a rig both audiences are one hide.
 * The restore for others is sent [hide]`.duration` ticks after their hide took effect.
 */
public class HeldItemHider(
    private val effects: IEntityEffectsGateway,
    private val scheduler: IScheduler,
) {

    private class State {
        var pendingTicks: Long = 0
        var ownerHidden: Boolean = false
        var othersHidden: Boolean = false
        var hiddenUntil: Long = 0
        var staged: Int = 0
        var token: Long = 0
    }

    private val states: MutableMap<PlayerId, State> = HashMap()

    /** Starts a hide of [duration] ticks for [target]; it takes effect with the staged rig's reveal, else this tick's end. */
    public fun hide(target: PlayerId, duration: Long) {
        val st = states.getOrPut(target) { State() }
        st.pendingTicks = maxOf(st.pendingTicks, duration)
        scheduler.runLater(ticks(0)) {
            if (states[target] === st && st.pendingTicks > 0 && st.staged == 0) {
                st.ownerHidden = true
                applyOthers(target, st, HeldItemAudience.ALL)
            }
        }
    }

    /** A rig for [target] is about to spawn hidden; returns `true` when a hide waits for its [reveal]. */
    public fun stage(target: PlayerId): Boolean {
        val st = states[target] ?: return false
        if (st.pendingTicks <= 0) return false
        st.staged++
        return true
    }

    /**
     * The staged rig's displays were made visible this tick. The owner's held item is hidden in this same tick (first-person
     * hand and rig never coincide, so no overlap); other viewers follow one full tick later (the rig's first keys equal the
     * held item's pose, so a one-tick overlap looks like one weapon, while a gap shows nothing).
     */
    public fun reveal(target: PlayerId) {
        val st = states[target] ?: return
        if (st.pendingTicks <= 0) return
        if (!st.ownerHidden) {
            effects.setHeldItemHidden(target, true, HeldItemAudience.OWNER)
            st.ownerHidden = true
        }
        scheduler.runLater(ticks(1)) {
            if (states[target] === st && st.pendingTicks > 0) applyOthers(target, st, HeldItemAudience.OTHERS)
        }
    }

    /**
     * A staged rig is ending (VfxPlayer calls this a full tick before its displays are removed): restore the held item for
     * other viewers now so it overlaps the rig for a tick, never leaving a frame with neither. The owner waits for
     * [rigRemoved]. Drops a hide that was never applied to others.
     */
    public fun rigEnded(target: PlayerId) {
        val st = states[target] ?: return
        if (st.staged > 0) st.staged--
        if (st.staged > 0) return
        // A hide that never reached other viewers has nothing to undo for them.
        if (!st.othersHidden) st.pendingTicks = 0
        if (st.othersHidden) {
            st.othersHidden = false
            effects.setHeldItemHidden(target, false, HeldItemAudience.OTHERS)
        }
        cleanup(target, st)
    }

    /** The staged rig's displays are removed this tick: the owner's held item comes back in the same tick. */
    public fun rigRemoved(target: PlayerId) {
        val st = states[target] ?: return
        if (st.staged > 0) return
        if (st.ownerHidden) {
            st.ownerHidden = false
            effects.setHeldItemHidden(target, false, HeldItemAudience.OWNER)
        }
        cleanup(target, st)
    }

    public fun shutdown() {
        states.entries.toList().forEach { (target, st) ->
            if (st.ownerHidden || st.othersHidden) effects.setHeldItemHidden(target, false, HeldItemAudience.ALL)
        }
        states.clear()
    }

    private fun applyOthers(target: PlayerId, st: State, audience: HeldItemAudience) {
        val duration = st.pendingTicks
        st.pendingTicks = 0
        effects.setHeldItemHidden(target, true, audience)
        st.othersHidden = true
        st.hiddenUntil = maxOf(st.hiddenUntil, scheduler.currentTick() + duration)
        val token = ++st.token
        // A later hide on the same target pushes the end out; only the last one restores the item.
        scheduler.runLater(ticks(duration)) {
            if (states[target] !== st || st.token != token) return@runLater
            if (scheduler.currentTick() < st.hiddenUntil) return@runLater
            if (st.staged == 0) restoreAll(target, st)
        }
    }

    private fun restoreAll(target: PlayerId, st: State) {
        st.ownerHidden = false
        st.othersHidden = false
        states.remove(target)
        effects.setHeldItemHidden(target, false, HeldItemAudience.ALL)
    }

    private fun cleanup(target: PlayerId, st: State) {
        if (!st.ownerHidden && !st.othersHidden && st.pendingTicks <= 0) states.remove(target)
    }
}
