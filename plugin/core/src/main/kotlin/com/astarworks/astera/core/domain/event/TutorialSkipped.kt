package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import java.time.Instant

/**
 * Emitted when a player explicitly skips a tutorial via
 * `/astera tutorial skip <id>` (Wave 5h, ADR-0079 §3).
 *
 * Published by `SkipTutorialUseCase` after the id is added to
 * `PlayerTutorialState.skippedTutorialScriptIds`. The first-join listener
 * treats skipped ids as "do not auto-start" so re-joins don't re-prompt; this
 * event is the observable signal of that contract change.
 *
 * Future subscribers (Wave 6a+): "did the player skip the default tutorial?"
 * onboarding analytics, optional follow-up reminders.
 */
public data class TutorialSkipped(
    val playerId: PlayerId,
    val tutorialId: TutorialScriptId,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
