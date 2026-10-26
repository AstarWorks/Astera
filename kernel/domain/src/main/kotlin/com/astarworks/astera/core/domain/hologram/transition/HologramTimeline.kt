package com.astarworks.astera.core.domain.hologram.transition

/**
 * Sequential composition of [HologramTransition]s with looping
 * (Wave 5transitions, ADR-0115 §C).
 *
 * The controller executes [transitions] one at a time — the next transition
 * starts after the previous one completes. When the final transition
 * completes, [loopMode] decides whether to wrap, ping-pong, or stop.
 *
 * # Why a separate type vs. `Composite`-with-list
 *
 * [HologramTransition.Composite] runs its children in **parallel** —
 * every child starts at the same instant. A timeline runs in **sequence**
 * — the next child starts only after the previous one completes. Mixing
 * the two semantics into one node would force the controller to inspect
 * an "isSequential" flag at every dispatch, defeating the sealed-when
 * design.
 *
 * Timelines are top-level only (not nestable inside a [HologramTransition]
 * variant) — a timeline's children are always plain transitions. If you
 * want a timeline of timelines, expand each into its own timeline played
 * via separate `play(...)` calls; that gives each its own handle for
 * independent cancellation.
 */
public data class HologramTimeline(
    public val transitions: List<HologramTransition>,
    public val loopMode: LoopMode = LoopMode.PlayOnce,
) {
    init {
        require(transitions.isNotEmpty()) { "HologramTimeline.transitions must not be empty" }
    }
}
