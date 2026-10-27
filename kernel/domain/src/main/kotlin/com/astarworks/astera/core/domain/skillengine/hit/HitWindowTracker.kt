package com.astarworks.astera.core.domain.skillengine.hit

import java.util.UUID

/**
 * Tracks which contacts of ONE hit op are let through, per its [HitWindow]. The runner keeps one
 * tracker per registered hit op (all of its frames share it).
 *  - Once: each victim at most once for the life of the tracker.
 *  - EveryTicks(n): each victim again only after n ticks since its last accepted contact.
 *  - PerPart: each (victim, hitter frame) pair once.
 */
public class HitWindowTracker(private val window: HitWindow) {
    private val lastTick = HashMap<Pair<UUID, String?>, Int>()

    /** True (and records it) when [contact] passes the window. */
    public fun accept(contact: ContactEvent): Boolean {
        val key = when (window) {
            HitWindow.PerPart -> contact.victim to contact.hitterFrame
            else -> contact.victim to null
        }
        val last = lastTick[key]
        val ok = when (window) {
            HitWindow.Once, HitWindow.PerPart -> last == null
            is HitWindow.EveryTicks -> last == null || contact.tick - last >= window.ticks
        }
        if (ok) lastTick[key] = contact.tick
        return ok
    }

    public fun filter(contacts: List<ContactEvent>): List<ContactEvent> = contacts.filter(::accept)

    public fun reset() {
        lastTick.clear()
    }
}
