package com.astarworks.astera.economy.application.usecase.policy

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.domain.event.CurrencyExpired
import com.astarworks.astera.economy.domain.policy.CurrencyPolicy
import java.time.Instant

/**
 * Periodic sweep (RTM #19, ADR-0035) — for every expirable [Currency], find
 * issuance rows older than [CurrencyPolicy.expirationDays], debit them from
 * the wallet, and publish [CurrencyExpired]. The currency-level policy
 * lookup uses the same `policyOf` injection as
 * [EnforceCurrencyPolicyUseCase].
 *
 * Phase C wires [sweep] into `IScheduler.repeatTicks` at a daily
 * interval (the orchestrator decides exact frequency). The [scheduler]
 * dependency is held so [sweep] can stamp [CurrencyExpired.occurredAtTick]
 * from the current server tick; it does not self-schedule.
 *
 * If a debit fails (wallet < record.outstandingAmount due to a bug
 * elsewhere), this implementation skips the offending row so other rows can
 * still expire on this pass; the discrepancy surfaces via the ledger's
 * existing audit event surface.
 */
public class ExpireCurrencyUseCase(
    private val issuanceLog: ICurrencyIssuanceLog,
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val policyOf: (Currency) -> CurrencyPolicy,
    private val clock: () -> Instant = Instant::now,
) {
    public suspend fun sweep() {
        Currency.entries.forEach { currency ->
            val policy = policyOf(currency)
            if (!policy.expirable) return@forEach
            val days = policy.expirationDays ?: return@forEach
            val expired = issuanceLog.findExpired(currency, clock(), days)
            expired.forEach { record ->
                // Debit the player's wallet (using debit at the ledger level).
                val withdrawResult = ledger.debit(record.playerId, currency, record.outstandingAmount)
                if (withdrawResult.isSuccess) {
                    issuanceLog.markConsumed(record.id, record.outstandingAmount)
                    eventBus.publish(
                        CurrencyExpired(
                            playerId = record.playerId,
                            currency = currency,
                            amount = record.outstandingAmount,
                            expiredAt = clock(),
                            originalIssuedAt = record.issuedAt,
                            occurredAtTick = scheduler.currentTick(),
                        ),
                    )
                }
                // If debit failed (player wallet < record.outstandingAmount due to
                // some bug), skip — audit log captures via the ledger's existing
                // event surface.
            }
        }
    }
}
