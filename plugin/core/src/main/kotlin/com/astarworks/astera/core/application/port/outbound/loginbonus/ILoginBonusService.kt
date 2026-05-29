package com.astarworks.astera.core.application.port.outbound.loginbonus

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.loginbonus.LoginBonusError
import com.astarworks.astera.core.domain.loginbonus.LoginBonusReward
import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.LocalDate

/**
 * Daily login-bonus claim flow + streak read accessor
 * (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * Phase 1 [com.astarworks.astera.core.application.service.loginbonus.DefaultLoginBonusService]
 * returns `Result.Failure(LoginBonusError.NotImplemented)` from [claimDaily] —
 * actual reward grant + streak increment ships in Phase 2.
 */
public interface ILoginBonusService {

    /**
     * Attempt to claim today's login bonus for [playerId]. Phase 2 will:
     *
     *  1. Look up the player's streak record (or treat absent as day 0).
     *  2. Reject if [today] equals `lastClaimDate`.
     *  3. Increment the streak (reset to 1 if there's a gap).
     *  4. Look up the [ILoginBonusRegistry] entry for the new streak day.
     *  5. Grant the reward (currency + items) via the relevant feature ports.
     *  6. Persist the updated streak record.
     *
     * Phase 1 returns [LoginBonusError.NotImplemented] without touching the
     * repository so test fixtures stay deterministic.
     */
    public fun claimDaily(
        playerId: PlayerId,
        today: LocalDate,
    ): Result<LoginBonusReward, LoginBonusError>

    /** Read accessor — exposed in Phase 1 so UI / GUI can show the streak. */
    public fun streakOf(playerId: PlayerId): LoginStreakRecord?
}
