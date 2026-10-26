package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import java.time.Instant

/**
 * Emitted by `RunTutorialUseCase` when a player enters a new tutorial step
 * (Wave 5e, RTM issue #17).
 *
 * Wave 5e publishes this event but does **not** subscribe to it — Paper-side
 * rendering (HUD message, actual teleport, item give) is deferred to Wave 6a,
 * which will register listeners that translate each variant into the
 * appropriate `IBroadcaster` / `IPlayerGateway` / `IWorldGateway` call.
 *
 * [stepIndex] is zero-based and identifies the step's position inside the
 * script's `steps` list, so listeners can correlate the event back to a
 * specific YAML entry (useful for analytics + replay testing).
 */
public data class TutorialStepEntered(
    val player: PlayerId,
    val scriptId: TutorialScriptId,
    val stepIndex: Int,
    val step: TutorialStep,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(player.uuid)
}
