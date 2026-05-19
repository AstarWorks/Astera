package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId

/**
 * Advance the in-flight tutorial cursor by one step (Wave 5h, ADR-0074 §3).
 *
 * Persisted-state mirror of `RunTutorialUseCase.advance`. Where `RunTutorialUseCase`
 * maintains the in-process `RunnerState` map and publishes
 * `TutorialStepEntered` for the per-step rendering listeners, this use case
 * owns the durable `PlayerTutorialState` slice and emits the lifecycle
 * [TutorialCompleted] signal when the script's final step is consumed.
 *
 * Flow:
 *  - No state for [playerId], or no [PlayerTutorialState.currentTutorial] →
 *    [AdvanceError.NotRunning].
 *  - Cursor's script id missing from the registry → [AdvanceError.NotInCatalog].
 *    State is left intact; callers may want to reset via [SkipTutorialUseCase].
 *  - Next index < script.steps.size → bump cursor, upsert, return success.
 *  - Next index ≥ script.steps.size → move id into completed, clear cursor,
 *    upsert, publish [TutorialCompleted], return success.
 *
 * Returns `true` when the advance completed the tutorial, `false` when more
 * steps remain. Useful for callers that want to chain a "show end-of-tutorial
 * reward" flow without re-loading the state.
 */
public class AdvanceTutorialStepUseCase(
    private val repo: IPlayerTutorialStateRepository,
    private val registry: ITutorialRegistry,
    private val events: IDomainEventBus,
) {

    public fun advance(playerId: PlayerId): Result<Boolean, AdvanceError> {
        val current = repo.find(playerId)
            ?: return Result.failure(AdvanceError.NotRunning(playerId))
        val activeId = current.currentTutorial
            ?: return Result.failure(AdvanceError.NotRunning(playerId))
        val currentIndex = current.currentStepIndex
            ?: return Result.failure(AdvanceError.NotRunning(playerId))

        val script = registry.find(activeId)
            ?: return Result.failure(AdvanceError.NotInCatalog(activeId))

        val nextIndex = currentIndex + 1
        return if (nextIndex >= script.steps.size) {
            // Final step consumed — complete + clear cursor.
            repo.upsert(current.markCompleted(activeId))
            events.publish(TutorialCompleted(playerId = playerId, tutorialId = activeId))
            Result.success(true)
        } else {
            repo.upsert(current.advanceStep())
            Result.success(false)
        }
    }
}

/**
 * Expected failure modes of [AdvanceTutorialStepUseCase]. Each variant carries
 * an i18n [messageKey] so command / event handlers can surface a localized
 * message via `IMessageRenderer` without leaking domain detail.
 */
public sealed class AdvanceError {
    public abstract val messageKey: MessageKey

    public data class NotRunning(val player: PlayerId) : AdvanceError() {
        override val messageKey: MessageKey =
            MessageKey("astera.tutorial.error.not-running")
    }

    public data class NotInCatalog(val scriptId: TutorialScriptId) : AdvanceError() {
        override val messageKey: MessageKey =
            MessageKey("astera.tutorial.error.not-in-catalog")
    }
}
