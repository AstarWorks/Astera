package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import java.time.Instant

/**
 * Fired when an active status effect's [remainingTicks] reached zero via
 * natural tick decrement (StatusEffectTickListener — Wave 6a-N2, ADR-0155).
 *
 * Distinct from [StatusRemoved] (manual mid-life removal — `/clear`, milk
 * bucket consumption, match end) and from
 * [com.astarworks.astera.core.domain.event.StatusEffectCancelled] (Wave 5o
 * interaction-matrix cancellation — ADR-0081).
 *
 * Subscribers:
 *  - HUD sidebar refresh
 *  - audit log: effect-survival duration statistics
 *  - skill mechanics: "on effect expiry → trigger X" hooks
 *
 * @property target player whose effect expired
 * @property effectId the expired effect's identifier
 * @property atTick server tick at which expiry was observed (one decrement
 *   loop after `remainingTicks` reached zero)
 */
public data class StatusExpired(
    val target: PlayerId,
    val effectId: StatusEffectId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
