package com.astarworks.astera.match.application.port.outbound.matchmaking

/**
 * What a queued player should see about their lane right now (ADR-0248): the sidebar and action bar read this
 * instead of guessing. Implemented by the queue scheduler, which owns the countdown.
 */
public fun interface IQueueProgress {
    public fun progressOf(laneKey: String): LaneProgress
}

/**
 * @property countdownSecondsLeft seconds until the lane forms its match; `null` when no countdown is running.
 * @property held the arena is busy / down, so a full lane still has to wait.
 */
public data class LaneProgress(
    val countdownSecondsLeft: Int? = null,
    val held: Boolean = false,
) {
    public companion object {
        public val IDLE: LaneProgress = LaneProgress()
    }
}
