package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import java.time.Instant

/**
 * Fired when an incoming status-effect application was cancelled (or an
 * existing effect was removed) by the Wave 5o status-effect interaction
 * matrix (ADR-0081).
 *
 * Carries both the incoming effect that triggered the interaction (so HUD /
 * audit can show "applied X cancelled Y") and the existing effect that was
 * cancelled, plus the matrix rule id for traceability.
 */
public data class StatusEffectCancelled(
    public val playerId: PlayerId,
    public val incomingEffectId: StatusEffectId,
    public val cancelledEffectId: StatusEffectId,
    public val ruleId: String,
    public val atTick: Long,
    public override val version: Int = 1,
    public override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
