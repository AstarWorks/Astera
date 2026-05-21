package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.event.TutorialStarted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId

/**
 * Replay [tutorialId] for [playerId] (Wave 5h, ADR-0079 §3).
 *
 * Backs `/astera tutorial replay <tutorialId>`. After replay:
 *
 *  - [tutorialId] is removed from both completed and skipped sets (the prior
 *    terminal status is cleared so the player can experience the script
 *    cleanly).
 *  - The cursor is set to ([tutorialId], step 0).
 *  - A [TutorialStarted] event is published.
 *
 * The downstream `RunTutorialUseCase` is expected to subscribe to
 * [TutorialStarted] (or be invoked from the same wire in Phase C) to drive
 * the per-step rendering loop. This use case owns the persisted state slice
 * only — it does not perform any Paper-side action.
 */
public class ReplayTutorialUseCase(
    private val repo: IPlayerTutorialStateRepository,
    private val events: IDomainEventBus,
) {

    public fun replay(playerId: PlayerId, tutorialId: TutorialScriptId) {
        val current = repo.find(playerId) ?: PlayerTutorialState(playerId = playerId)
        repo.upsert(current.startTutorial(tutorialId))
        events.publish(TutorialStarted(playerId = playerId, tutorialId = tutorialId))
    }
}
