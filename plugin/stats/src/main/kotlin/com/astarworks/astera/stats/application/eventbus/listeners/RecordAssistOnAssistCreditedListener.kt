package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.AssistCredited
import com.astarworks.astera.stats.application.usecase.stats.RecordAssistUseCase

/**
 * Translates [AssistCredited] events from the combat plugin into stats
 * persistence updates via [RecordAssistUseCase]. ADR-0063.
 *
 * Sibling-isolation matrix (ADR-0022): Stats ← Combat is permitted, so
 * importing `combat.domain.event.AssistCredited` here is the canonical
 * cross-plugin event-subscription pattern (cf. existing
 * `RewardOnKillListener` subscribing to `PlayerKilled`).
 */
public class RecordAssistOnAssistCreditedListener(
    private val recordAssist: RecordAssistUseCase,
) {
    public fun handle(event: AssistCredited) {
        recordAssist.execute(event.player)
    }
}
