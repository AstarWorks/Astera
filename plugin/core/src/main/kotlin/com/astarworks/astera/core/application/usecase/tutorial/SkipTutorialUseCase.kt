package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.event.TutorialSkipped
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId

/**
 * Mark [tutorialId] as skipped for [playerId] (Wave 5h, ADR-0074 §3).
 *
 * Backs `/astera tutorial skip <tutorialId>`. After skipping:
 *
 *  - The id lives in [PlayerTutorialState.skippedTutorialScriptIds].
 *  - The cursor is cleared if [tutorialId] matches the in-flight tutorial
 *    (so the player isn't stuck mid-step after skipping).
 *  - A [TutorialSkipped] event is published.
 *
 * `RunTutorialUseCase`'s in-memory `RunnerState` is not touched here; the
 * Phase C wiring is expected to subscribe a small listener that calls
 * `RunTutorialUseCase.cancel(playerId)` on [TutorialSkipped] when the
 * cancel/cleanup surface lands. For Phase 1 the cursor in the persisted
 * state is the source of truth for "what step is the player on" since
 * `RunnerState` is re-built from it on rejoin.
 *
 * Idempotent: skipping an already-skipped tutorial is a no-op set union;
 * the event still publishes so analytics observe the deliberate action.
 */
public class SkipTutorialUseCase(
    private val repo: IPlayerTutorialStateRepository,
    private val events: IDomainEventBus,
) {

    public fun skip(playerId: PlayerId, tutorialId: TutorialScriptId) {
        val current = repo.find(playerId) ?: PlayerTutorialState(playerId = playerId)
        repo.upsert(current.markSkipped(tutorialId))
        events.publish(TutorialSkipped(playerId = playerId, tutorialId = tutorialId))
    }
}
