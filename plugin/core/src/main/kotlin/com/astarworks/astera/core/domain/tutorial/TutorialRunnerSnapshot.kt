package com.astarworks.astera.core.domain.tutorial

/**
 * Per-player sequencer cursor for [TutorialScript] (Wave 5p, ADR-0088).
 *
 * Persisted via [com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository]
 * so a tutorial in flight survives a server restart (Phase 1 PoC) or a
 * cross-server proxy hop (Wave _persist-1's ADR-0068 boundary).
 *
 * # Wave 5e relation
 *
 * Wave 5e's [com.astarworks.astera.core.application.usecase.tutorial.RunTutorialUseCase]
 * keeps its in-memory map for backwards-compat — its callers don't yet need
 * persistence. The new sequencer (Wave 5p γ) is the persistent path; both
 * coexist until Wave 6a unifies them.
 */
public data class TutorialRunnerSnapshot(
    public val scriptId: TutorialScriptId,
    public val stepIndex: Int,
    public val startedAtTick: Long,
) {
    init {
        require(stepIndex >= 0) { "TutorialRunnerSnapshot.stepIndex must be >= 0, got $stepIndex" }
    }
}
