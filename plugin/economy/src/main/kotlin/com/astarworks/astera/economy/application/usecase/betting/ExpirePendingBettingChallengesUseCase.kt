package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingStatus
import java.time.Clock

/**
 * Sweeps the repository for [BettingStatus.PendingAcceptance] matches whose
 * `acceptanceDeadline` has passed, marks them Expired, and returns the
 * expired list.
 *
 * Phase 1 invocation: scheduled per-minute by `AsteraEconomyPlugin`. No
 * escrow movement — expiry is pre-acceptance. (Wave 5bet / ADR-0103
 * §"Workflow" `acceptanceTimeout`.)
 */
public class ExpirePendingBettingChallengesUseCase(
    private val matches: IBettingMatchRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    public fun execute(): List<BettingMatch> {
        val now = clock.instant()
        return matches.findByStatus(BettingStatus.PendingAcceptance)
            .filter { it.acceptanceDeadline.isBefore(now) }
            .map { matches.save(it.expire()) }
    }
}
