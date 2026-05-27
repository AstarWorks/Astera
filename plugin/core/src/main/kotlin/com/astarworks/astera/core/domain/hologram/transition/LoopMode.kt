package com.astarworks.astera.core.domain.hologram.transition

/**
 * Looping behaviour for a [HologramTimeline] (Wave 5transitions, ADR-0115 §C).
 *
 * The default is [PlayOnce] — execute every transition sequentially and
 * complete. [Loop] re-runs the whole transition list in order. [PingPong]
 * alternates forward and reverse traversals.
 *
 * `times = null` means infinite — the timeline runs until the controller
 * is cancelled or its parent hologram is despawned. `times = N` means
 * **N total cycles** of the loop body — `times = 1` plays the timeline
 * once and completes (degenerate but allowed for symmetric API use);
 * `times = 3` runs 3 full traversals for `Loop` and 3 (forward + backward)
 * cycles for `PingPong`.
 *
 * `times` is rejected at construction for non-positive finite values
 * (`times = 0` would mean "play zero times", which is ill-defined; callers
 * who want to skip a transition should not schedule it).
 */
public sealed interface LoopMode {

    public data object PlayOnce : LoopMode

    /**
     * Re-run the transition list in order. `null` = infinite.
     *
     * Forward → forward → forward → ... — every iteration starts from the
     * first transition. If the timeline body mutates a tweened field, the
     * loop re-applies from the same starting state seen at the start of
     * the previous iteration (controller does not snapshot intermediate
     * state).
     *
     * `times` = total iterations to play. `times = 3` runs the body 3
     * full times then completes. `null` = infinite.
     */
    public data class Loop(public val times: Int?) : LoopMode {
        init {
            if (times != null) {
                require(times > 0) { "Loop.times must be > 0 or null (infinite), got $times" }
            }
        }
    }

    /**
     * Alternate forward and reverse traversals. `null` = infinite.
     *
     * `[A, B, C]` plays as `A → B → C → C → B → A → A → B → C → ...`. Each
     * back-traversal re-runs the same transition list with the same target
     * values; the visual reversal comes from the **state** mid-traversal,
     * not from any inverted easing. If a smooth transition targets
     * `(x, y, z)` going forward, the back-traversal also targets the same
     * `(x, y, z)` — but starting from the position the forward traversal
     * ended at, so the net visual is `forward → backward`.
     *
     * `times` counts loop **cycles**, where one cycle = one forward + one
     * backward traversal. `times = 1` means 1 cycle = 1 forward + 1
     * backward = 2 traversals total. `times = null` = infinite.
     */
    public data class PingPong(public val times: Int?) : LoopMode {
        init {
            if (times != null) {
                require(times > 0) { "PingPong.times must be > 0 or null (infinite), got $times" }
            }
        }
    }
}
