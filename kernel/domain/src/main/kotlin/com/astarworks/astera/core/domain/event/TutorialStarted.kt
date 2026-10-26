package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import java.time.Instant

/**
 * Emitted when a tutorial transitions a player from "not running" to
 * "running step 0" (Wave 5h, ADR-0079 §3).
 *
 * Distinct from [TutorialStepEntered], which fires for every step (including
 * step 0). [TutorialStarted] is the higher-level lifecycle signal: analytics
 * dashboards and onboarding telemetry care about "how many players started
 * the default tutorial", not the per-step rendering details.
 *
 * Published by `StartTutorialOnFirstJoinListener` (auto-trigger path) and
 * `ReplayTutorialUseCase` (explicit replay path). Step rendering is still
 * driven by the `TutorialStepEntered` listeners — this event is informational.
 */
public data class TutorialStarted(
    val playerId: PlayerId,
    val tutorialId: TutorialScriptId,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
