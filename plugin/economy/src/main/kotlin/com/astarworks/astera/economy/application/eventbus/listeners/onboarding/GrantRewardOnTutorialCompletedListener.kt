package com.astarworks.astera.economy.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import org.slf4j.LoggerFactory

/**
 * On [TutorialCompleted] deposit a fixed STAR reward into the player's
 * wallet (Wave K 5integration-wire, ADR-0128).
 *
 * # Why STAR (and not GOLD)
 *
 * STAR is match-bound and consumed in the first match (skill activation
 * costs). Giving the graduate 50 STAR seeds their first lobby skill cast
 * without changing their account economy — graduates don't accidentally
 * inflate their persistent GOLD balance before their first match runs.
 * GOLD is reserved for match completion + dailies (`Currency` docstring).
 *
 * # Why a separate listener (and not the achievement listener's reward
 * fan-out)
 *
 * Achievement rewards (currency / item / title) go through the
 * `AdvancementRewardGranted` event channel and require the achievement
 * to *have* a reward declared in YAML. Onboarding's STAR seed lives
 * outside that flow because:
 *
 *  1. The reward must fire even if the operator never authored
 *     `tutorial-graduate.yaml` (defensive — onboarding shouldn't fail
 *     silently because of a YAML omission).
 *  2. Wave 5 PoC has no `grant_currency: { currency: STAR }` polymorphic
 *     variant in `AdvancementRewardDto` (only GOLD examples land in
 *     content); rather than push schema scope into Wave K, the deposit
 *     happens inline.
 *
 * Both listeners subscribe to the same event independently; they don't
 * coordinate. If both succeed, the player sees the achievement *and*
 * the credit.
 *
 * # Failure modes
 *
 * [DepositUseCase] only fails on non-positive amounts, which a literal
 * cannot produce. Any I/O failure inside the ledger is a process-wide
 * problem and would have surfaced earlier; we log at WARN as a safety
 * net.
 */
public class GrantRewardOnTutorialCompletedListener(
    private val deposit: DepositUseCase,
    private val currency: Currency = DEFAULT_CURRENCY,
    private val amount: Long = DEFAULT_AMOUNT,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(amount > 0L) {
            "GrantRewardOnTutorialCompletedListener.amount must be > 0: $amount"
        }
    }

    public fun handle(event: TutorialCompleted) {
        when (val outcome = deposit.execute(
            playerId = event.playerId,
            currency = currency,
            amount = amount,
            reason = CurrencyChangeReason.TutorialReward,
        )) {
            is Result.Success -> {
                log.info(
                    "Tutorial graduation reward credited: player={} tutorial={} {}+={}",
                    event.playerId, event.tutorialId, currency, amount,
                )
            }
            is Result.Failure -> {
                log.warn(
                    "Tutorial graduation reward FAILED: player={} tutorial={} reason={}",
                    event.playerId, event.tutorialId, outcome.error,
                )
            }
        }
    }

    public companion object {
        /** Match-bound currency (Wave 5 PoC default). */
        public val DEFAULT_CURRENCY: Currency = Currency.STAR

        /**
         * 50 STAR — enough to seed two or three first-match skill casts.
         * Tuning lives here for Wave K; a YAML-driven version lands in a
         * later wave once `content/onboarding/` exists.
         */
        public const val DEFAULT_AMOUNT: Long = 50L
    }
}
