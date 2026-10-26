package com.astarworks.astera.economy.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when [com.astarworks.astera.economy.application.usecase.policy.ExpireCurrencyUseCase]
 * removes expired currency from a wallet (RTM #19, JP 資金決済法 compliance).
 *
 * Downstream listeners (audit log, player notification) react to the event;
 * the use case itself only performs the debit + issuance-log mark-consumed.
 *
 * Wave 5f (ADR-0035).
 */
public data class CurrencyExpired(
    public val playerId: PlayerId,
    public val currency: Currency,
    public val amount: Long,
    public val expiredAt: Instant,
    public val originalIssuedAt: Instant,
    public val occurredAtTick: Long,
    override val occurredAt: Instant = expiredAt,
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
