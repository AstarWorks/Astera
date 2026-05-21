package com.astarworks.astera.core.domain.quest

/**
 * Outcome of a single `IQuestService.progressCurrent` call (Wave 5s, ADR-0091).
 *
 * Three discrete states the runtime may transition to after applying an
 * increment:
 *
 *  - [StillInProgress] — the current stage's counter advanced but the target
 *    has not been reached. The player remains on [PlayerQuestProgress.currentStageId].
 *  - [StageAdvanced] — the current stage's target was reached; rewards were
 *    dispatched and [PlayerQuestProgress.currentStageId] was overwritten with
 *    [next.id]. [com.astarworks.astera.core.domain.event.QuestStageCompleted]
 *    was published before this returned.
 *  - [ChainCompleted] — the terminal stage cleared; the entire chain is now
 *    complete. [com.astarworks.astera.core.domain.event.QuestStageCompleted]
 *    and [com.astarworks.astera.core.domain.event.QuestChainCompleted] were
 *    both published before this returned.
 */
public sealed interface QuestStageOutcome {

    /** The current stage advanced but did not complete. */
    public data class StillInProgress(public val progress: PlayerQuestProgress) : QuestStageOutcome

    /** A stage completed; the player auto-advanced to [next]. */
    public data class StageAdvanced(
        public val completed: QuestStage,
        public val next: QuestStage,
        public val progress: PlayerQuestProgress,
    ) : QuestStageOutcome

    /** The chain's terminal stage completed; the chain is fully cleared. */
    public data class ChainCompleted(
        public val completed: QuestStage,
        public val progress: PlayerQuestProgress,
    ) : QuestStageOutcome
}
