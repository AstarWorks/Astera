package com.astarworks.astera.combat.application.service

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
 * [reveal] only schedules the hide one full server tick later (held item and rig overlap for a tick; the rig's first keys
 * equal the held item's pose, so the overlap reads as one weapon). The restore is sent [hide]`.duration` ticks after the
 * hide took effect; while a staged rig is still alive it waits for [rigEnded], which VfxPlayer runs a full tick before the
 * rig's displays are removed.
 */
public class HeldItemHider(
    private val effects: IEntityEffectsGateway,
    private val scheduler: IScheduler,
) {

    private class State {
        var pendingTicks: Long = 0
        var hidden: Boolean = false
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
            if (states[target] === st && st.pendingTicks > 0 && st.staged == 0) apply(target, st)
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
     * The staged rig's displays were made visible this tick. The hide follows one full tick later: the rig's first keys
     * are exactly the held item's pose, so held item + rig overlapping for a tick looks like one weapon, while a gap
     * (the client needs a tick before a new display draws) shows nothing.
     */
    public fun reveal(target: PlayerId) {
        val st = states[target] ?: return
        if (st.pendingTicks <= 0) return
        scheduler.runLater(ticks(1)) {
            if (states[target] === st && st.pendingTicks > 0) apply(target, st)
        }
    }

    /**
     * A staged rig is ending (VfxPlayer calls this a full tick before its displays are removed): restore the held item now
     * so it overlaps the rig for a tick, never leaving a frame with neither. Drops a hide that was never applied.
     */
    public fun rigEnded(target: PlayerId) {
        val st = states[target] ?: return
        if (st.staged > 0) st.staged--
        if (st.staged > 0) return
        if (st.pendingTicks > 0 && !st.hidden) {
            // The rig died before its reveal: nothing replaced the weapon, so there is nothing to hide.
            st.pendingTicks = 0
            states.remove(target)
            return
        }
        if (st.hidden) restore(target, st)
    }

    public fun shutdown() {
        states.entries.toList().forEach { (target, st) -> if (st.hidden) effects.setHeldItemHidden(target, false) }
        states.clear()
    }

    private fun apply(target: PlayerId, st: State) {
        val duration = st.pendingTicks
        st.pendingTicks = 0
        effects.setHeldItemHidden(target, true)
        st.hidden = true
        st.hiddenUntil = maxOf(st.hiddenUntil, scheduler.currentTick() + duration)
        val token = ++st.token
        // A later hide on the same target pushes the end out; only the last one restores the item.
        scheduler.runLater(ticks(duration)) {
            if (states[target] !== st || st.token != token) return@runLater
            if (scheduler.currentTick() < st.hiddenUntil) return@runLater
            if (st.staged == 0) restore(target, st)
        }
    }

    private fun restore(target: PlayerId, st: State) {
        st.hidden = false
        states.remove(target)
        effects.setHeldItemHidden(target, false)
    }
}
