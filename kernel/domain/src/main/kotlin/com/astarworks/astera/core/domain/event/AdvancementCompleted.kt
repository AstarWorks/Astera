package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A custom advancement was earned by [playerId] (Wave 5p, ADR-0088).
 *
 * Published once per player × advancement combination — the
 * `IPlayerAdvancementProgressRepository` latches `completedAt`, so a repeat
 * trigger after completion is a no-op (no duplicate event). Reward dispatch
 * (currency / item / title / achievement) runs synchronously in the use case
 * that publishes this event, so subscribers can treat the event as
 * "completed AND rewards delivered".
 *
 * Downstream subscribers (Wave 6a HUD, analytics) consume this to render
 * toast notifications, write audit log rows, or feed Wave 5u KPI dashboards.
 */
public data class AdvancementCompleted(
    public val playerId: PlayerId,
    public val advancementId: AdvancementId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
