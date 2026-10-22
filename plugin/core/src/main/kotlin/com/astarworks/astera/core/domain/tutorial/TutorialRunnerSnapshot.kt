package com.astarworks.astera.core.domain.tutorial

/** Durable cursor, explicit pause, and graduation latch (ADR-0235).
 * Completion retains the row; starting a replay carries the reward latch forward.
 */
public data class TutorialRunnerSnapshot(
    public val scriptId: TutorialScriptId,
    public val stepIndex: Int,
    public val startedAtTick: Long,
    public val paused: Boolean = false,
    public val completed: Boolean = false,
    public val rewardGranted: Boolean = false,
) {
    init {
        require(stepIndex >= 0) { "TutorialRunnerSnapshot.stepIndex must be >= 0, got $stepIndex" }
    }
}
