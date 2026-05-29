package com.astarworks.astera.core.application.service.loginbonus

import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusRegistry
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.loginbonus.LoginBonusError
import com.astarworks.astera.core.domain.loginbonus.LoginBonusReward
import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.LocalDate

/**
 * Phase 1 skeleton implementation of [ILoginBonusService]
 * (Wave J 5server-content-ops, ADR-0127).
 *
 * [claimDaily] returns `Result.Failure(LoginBonusError.NotImplemented)` unconditionally
 * — actual streak increment + reward grant lands in Phase 2. The repository +
 * registry dependencies are accepted now so Phase 2 can fill the body without
 * changing the plugin wiring.
 *
 * [streakOf] proxies to the repository so any UI that wants to read a streak
 * already works against this Phase 1 skeleton.
 */
public class DefaultLoginBonusService(
    @Suppress("unused") private val registry: ILoginBonusRegistry,
    private val streakRepo: ILoginStreakRepository,
) : ILoginBonusService {

    override fun claimDaily(
        playerId: PlayerId,
        today: LocalDate,
    ): Result<LoginBonusReward, LoginBonusError> = Result.failure(LoginBonusError.NotImplemented)

    override fun streakOf(playerId: PlayerId): LoginStreakRecord? =
        streakRepo.findByPlayer(playerId)
}
