package com.astarworks.astera.core.application.port.outbound.loginbonus

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.loginbonus.LoginBonusClaim
import com.astarworks.astera.core.domain.loginbonus.LoginBonusError
import com.astarworks.astera.core.domain.loginbonus.LoginBonusStatus
import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.LocalDate

/**
 * Daily login-bonus claim flow + streak read accessors (ADR-0127, claim flow ADR-0210).
 *
 * "Today" is the caller's calendar day (server zone). The streak continues only when the
 * previous claim was exactly yesterday; any longer gap restarts it at 1. The configured
 * rotation wraps: streak 8 on a 7-day table pays day 1 again.
 */
public interface ILoginBonusService {

    /**
     * Claim today's bonus: [LoginBonusError.AlreadyClaimedToday] if [today] was already claimed,
     * otherwise advance the streak, credit the reward, save the streak and publish
     * `LoginBonusClaimed`.
     */
    public suspend fun claimDaily(
        playerId: PlayerId,
        today: LocalDate,
    ): Result<LoginBonusClaim, LoginBonusError>

    /** What claiming (or having claimed) today means for [playerId]; nothing is written. */
    public suspend fun status(playerId: PlayerId, today: LocalDate): LoginBonusStatus

    /** Raw streak record, or `null` if the player never claimed. */
    public suspend fun streakOf(playerId: PlayerId): LoginStreakRecord?
}
