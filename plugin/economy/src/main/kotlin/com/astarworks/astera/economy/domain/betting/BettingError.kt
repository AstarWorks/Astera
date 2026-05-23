package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Sealed failure space for the betting use cases (Wave 5bet / ADR-0103).
 *
 * Each variant carries an [messageKey] for i18n display. The errors are
 * **never** raw exceptions: per the Konsist `application layers must not
 * import raw JVM throwable types` rule + CLAUDE.md §"Application API surface".
 */
public sealed class BettingError {
    public abstract val messageKey: MessageKey

    /** [policyId] does not resolve in the registry. */
    public data class UnknownPolicy(val policyId: BettingPolicyId) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.unknown_policy")
    }

    /** [currency] is not in the policy's `allowedCurrencies`. */
    public data class CurrencyNotAllowed(val currency: Currency) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.currency_not_allowed")
    }

    /** Stake exceeds [BettingPolicy.maxStakePerMatch]. */
    public data class StakeExceedsCap(val attempted: Long, val cap: Long) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.stake_exceeds_cap")
    }

    /** Daily aggregate stake would exceed [BettingPolicy.perCurrencyDailyStakeCap]. */
    public data class DailyCapWouldBeExceeded(
        val playerId: PlayerId,
        val currency: Currency,
        val usedToday: Long,
        val attempted: Long,
        val cap: Long,
    ) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.daily_cap_exceeded")
    }

    /** Challenger is still cooling down from a previous match. */
    public data class CooldownActive(val playerId: PlayerId, val cooldownEndsAt: Instant) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.cooldown_active")
    }

    /** Player has hit [BettingPolicy.lossStreakLimit] consecutive losses. */
    public data class LossStreakLimitReached(
        val playerId: PlayerId,
        val streak: Int,
        val limit: Int,
        val cooldownEndsAt: Instant,
    ) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.loss_streak_limit")
    }

    /** Challenger and opponent are the same player. */
    public data class SelfBettingForbidden(val playerId: PlayerId) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.self_betting_forbidden")
    }

    /** The challenger or opponent has betting disabled in their PlayerSettings. */
    public data class PlayerOptedOut(val playerId: PlayerId) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.player_opted_out")
    }

    /** [BettingMatchId] does not exist in the repository. */
    public data class UnknownMatch(val id: BettingMatchId) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.unknown_match")
    }

    /** Status transition is not legal from the current status. */
    public data class NotPending(val id: BettingMatchId, val actual: BettingStatus) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.not_pending")
    }

    /** Status transition is not legal — match is not Active. */
    public data class NotActive(val id: BettingMatchId, val actual: BettingStatus) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.not_active")
    }

    /** Acceptance deadline has passed. */
    public data class AcceptanceDeadlineExpired(val id: BettingMatchId, val deadline: Instant) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.acceptance_expired")
    }

    /** A player who is neither challenger nor opponent attempted to accept. */
    public data class NotInvited(val id: BettingMatchId, val attempted: PlayerId) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.not_invited")
    }

    /** Wallet debit failed (insufficient funds). */
    public data class EscrowDebitFailed(
        val playerId: PlayerId,
        val cause: WalletError,
    ) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.escrow_debit_failed")
    }

    /** Both stakes have already been escrowed. */
    public data class AlreadyEscrowed(val id: BettingMatchId) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.already_escrowed")
    }

    /** Escrow is in an unexpected state for the requested operation. */
    public data class EscrowMissing(val id: BettingMatchId) : BettingError() {
        override val messageKey: MessageKey = MessageKey("astera.betting.escrow_missing")
    }
}
