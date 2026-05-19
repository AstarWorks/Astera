package com.astarworks.astera.core.domain.tutorial

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Per-player tutorial completion / skip / current-step state (Wave 5h, ADR-0074
 * §3). Persisted in `player_tutorial_state` (V20 migration).
 *
 * The aggregate carries three logical slices:
 *
 *  - [completedTutorialScriptIds] — scripts the player has fully walked through.
 *  - [skippedTutorialScriptIds] — scripts the player explicitly opted out of via
 *    `/astera tutorial skip <id>`. The first-join listener treats "skipped" as
 *    "do not auto-start" so re-joins don't re-prompt.
 *  - [currentTutorial] + [currentStepIndex] — the in-flight cursor (nullable;
 *    `null` means "no tutorial running for this player"). The cursor is what
 *    `RunTutorialUseCase` rebuilds its in-memory `RunnerState` from on rejoin.
 *
 * All mutators are pure: they return a new instance with `updatedAt = now()`
 * so callers can pass the result straight to [IPlayerTutorialStateRepository.upsert].
 *
 * Drives:
 *  - `StartTutorialOnFirstJoinListener`: empty [completedTutorialScriptIds] +
 *    [skippedTutorialScriptIds] not containing default → start default tutorial.
 *  - `SkipTutorialUseCase`: [markSkipped] + clear cursor if same id.
 *  - `ReplayTutorialUseCase`: remove from completed + skipped, [startTutorial].
 *  - `AdvanceTutorialStepUseCase`: [advanceStep] or [markCompleted] on last step.
 */
public data class PlayerTutorialState(
    public val playerId: PlayerId,
    public val completedTutorialScriptIds: Set<TutorialScriptId> = emptySet(),
    public val skippedTutorialScriptIds: Set<TutorialScriptId> = emptySet(),
    public val currentTutorial: TutorialScriptId? = null,
    public val currentStepIndex: Int? = null,
    public val updatedAt: Instant = Instant.EPOCH,
) {

    /**
     * Mark [id] as completed. Removes from skipped if present (a player who
     * finishes a previously-skipped tutorial should no longer count as having
     * skipped it). Clears the cursor when [id] matches the in-flight tutorial.
     */
    public fun markCompleted(id: TutorialScriptId, now: Instant = Instant.now()): PlayerTutorialState =
        copy(
            completedTutorialScriptIds = completedTutorialScriptIds + id,
            skippedTutorialScriptIds = skippedTutorialScriptIds - id,
            currentTutorial = if (currentTutorial == id) null else currentTutorial,
            currentStepIndex = if (currentTutorial == id) null else currentStepIndex,
            updatedAt = now,
        )

    /**
     * Mark [id] as skipped. Clears the cursor when [id] matches the in-flight
     * tutorial. Idempotent — adding an already-skipped id is a no-op set union.
     */
    public fun markSkipped(id: TutorialScriptId, now: Instant = Instant.now()): PlayerTutorialState =
        copy(
            skippedTutorialScriptIds = skippedTutorialScriptIds + id,
            currentTutorial = if (currentTutorial == id) null else currentTutorial,
            currentStepIndex = if (currentTutorial == id) null else currentStepIndex,
            updatedAt = now,
        )

    /**
     * Begin [id] from step 0. Removes [id] from both completed and skipped so
     * a replay clears its prior terminal status. The previously running tutorial
     * (if any) is dropped without recording completion — callers wanting to
     * preserve progress should advance it first.
     */
    public fun startTutorial(id: TutorialScriptId, now: Instant = Instant.now()): PlayerTutorialState =
        copy(
            completedTutorialScriptIds = completedTutorialScriptIds - id,
            skippedTutorialScriptIds = skippedTutorialScriptIds - id,
            currentTutorial = id,
            currentStepIndex = 0,
            updatedAt = now,
        )

    /**
     * Increment [currentStepIndex] by one. Throws [IllegalStateException] when
     * no tutorial is active — callers must check [currentTutorial] first (or
     * use [AdvanceTutorialStepUseCase] which surfaces the error as a `Result`).
     */
    public fun advanceStep(now: Instant = Instant.now()): PlayerTutorialState {
        check(currentTutorial != null && currentStepIndex != null) {
            "advanceStep called with no active tutorial for player $playerId"
        }
        return copy(
            currentStepIndex = currentStepIndex + 1,
            updatedAt = now,
        )
    }

    /** Clear the in-flight cursor without recording completion or skip. */
    public fun clearCurrent(now: Instant = Instant.now()): PlayerTutorialState =
        copy(
            currentTutorial = null,
            currentStepIndex = null,
            updatedAt = now,
        )
}
