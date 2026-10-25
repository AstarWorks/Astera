package com.astarworks.astera.core.domain.loginbonus

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed error hierarchy for the login-bonus claim flow
 * (Wave J 5server-content-ops, ADR-0127; claim flow ADR-0210).
 */
public sealed class LoginBonusError {
    public abstract val messageKey: MessageKey

    /** The player has already claimed today's bonus. */
    public data object AlreadyClaimedToday : LoginBonusError() {
        override val messageKey: MessageKey =
            MessageKey("astera.loginbonus.error.already_claimed_today")
    }

    /** The configured table has no entry for the player's current streak day. */
    public data class NoRewardForDay(public val day: Int) : LoginBonusError() {
        override val messageKey: MessageKey =
            MessageKey("astera.loginbonus.error.no_reward_for_day")
    }
}
