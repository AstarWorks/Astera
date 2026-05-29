package com.astarworks.astera.core.domain.loginbonus

/**
 * Reward awarded on the [day]-th consecutive day of login
 * (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * `day` is 1-indexed (day 1 = first claim of a streak). Authors typically
 * ship a contiguous 7-day rotation; gaps in the table are allowed and
 * skipped at claim time (Phase 2 chooses whether to wrap or fall back).
 */
public data class LoginBonusSpec(
    public val day: Int,
    public val reward: LoginBonusReward,
) {
    init {
        require(day >= 1) { "day must be >= 1 (got $day)" }
    }
}
