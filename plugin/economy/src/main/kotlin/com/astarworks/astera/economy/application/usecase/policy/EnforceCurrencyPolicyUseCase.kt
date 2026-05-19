package com.astarworks.astera.economy.application.usecase.policy

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.domain.policy.CurrencyPolicy

/**
 * Application-layer gate (RTM #19, ADR-0035). Composed into
 * [com.astarworks.astera.economy.application.usecase.currency.TransferUseCase]
 * / [com.astarworks.astera.economy.application.usecase.currency.DepositUseCase]
 * at Phase C. Returns sealed [CurrencyPolicyError] when:
 *  - [CurrencyPolicy.tradable] = false but a Transfer is attempted
 *  - [CurrencyPolicy.redeemable] = false but a Redeem is attempted
 *  - [CurrencyPolicy.circulationCap] would be exceeded by a Deposit
 *
 * Resolves the relevant [CurrencyPolicy] via [policyOf]; the production
 * binding is a `Map<Currency, CurrencyPolicy>` provided at plugin enable.
 */
public class EnforceCurrencyPolicyUseCase(
    private val policyOf: (Currency) -> CurrencyPolicy,
    private val issuanceLog: ICurrencyIssuanceLog,
) {
    public fun checkTransfer(currency: Currency, from: PlayerId, to: PlayerId, amount: Long): Result<Unit, CurrencyPolicyError> {
        val policy = policyOf(currency)
        if (!policy.tradable) return Result.Failure(CurrencyPolicyError.TransferBlocked(currency))
        return Result.Success(Unit)
    }

    public fun checkDeposit(currency: Currency, playerId: PlayerId, amount: Long): Result<Unit, CurrencyPolicyError> {
        val policy = policyOf(currency)
        val cap = policy.circulationCap ?: return Result.Success(Unit)
        val outstanding = issuanceLog.totalOutstanding(currency)
        if (outstanding + amount > cap) {
            return Result.Failure(CurrencyPolicyError.CapExceeded(currency, cap, outstanding + amount))
        }
        return Result.Success(Unit)
    }

    public fun checkRedeem(currency: Currency): Result<Unit, CurrencyPolicyError> {
        val policy = policyOf(currency)
        if (!policy.redeemable) return Result.Failure(CurrencyPolicyError.RedeemBlocked(currency))
        return Result.Success(Unit)
    }
}

public sealed class CurrencyPolicyError {
    public abstract val messageKey: MessageKey

    public data class TransferBlocked(val currency: Currency) : CurrencyPolicyError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.policy.error.transfer_blocked")
    }
    public data class CapExceeded(val currency: Currency, val cap: Long, val attempted: Long) : CurrencyPolicyError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.policy.error.cap_exceeded")
    }
    public data class RedeemBlocked(val currency: Currency) : CurrencyPolicyError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.policy.error.redeem_blocked")
    }
    public data class Expired(val currency: Currency) : CurrencyPolicyError() {
        override val messageKey: MessageKey = MessageKey("astera.economy.policy.error.expired")
    }
}
