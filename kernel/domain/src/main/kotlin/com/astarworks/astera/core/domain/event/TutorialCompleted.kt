package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import java.time.Instant

/**
 * Emitted when a player walks through the final step of a tutorial (Wave 5h,
 * ADR-0079 §3).
 *
 * Published by `AdvanceTutorialStepUseCase` when its `++currentStepIndex` lands
 * past the last step in the script. The use case also moves the id into
 * `PlayerTutorialState.completedTutorialScriptIds` and clears the cursor
 * (the lifecycle-state mutation is atomic with the event publish).
 *
 * Future subscribers (Wave 6a+): mission-progress hooks ("first tutorial
 * complete" achievement), onboarding telemetry counters.
 */
public data class TutorialCompleted(
    val playerId: PlayerId,
    val tutorialId: TutorialScriptId,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
