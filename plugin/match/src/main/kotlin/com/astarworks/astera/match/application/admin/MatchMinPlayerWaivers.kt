package com.astarworks.astera.match.application.admin

import com.astarworks.astera.core.domain.match.MatchId
import java.util.concurrent.ConcurrentHashMap

/**
 * Matches an admin forced past the roster floor from the `/astera-admin`
 * panel (F1-D, ADR-0182). `SwitchPhaseUseCase` consults [isWaived] before the
 * COUNTDOWN to ACTIVE min-player guard, so a lone playtester's force-started
 * match is not stuck in COUNTDOWN waiting for players that never come.
 */
public class MatchMinPlayerWaivers {

    private val waived: MutableSet<String> = ConcurrentHashMap.newKeySet()

    public fun waive(matchId: MatchId) {
        waived += matchId.value
    }

    public fun isWaived(matchId: MatchId): Boolean = matchId.value in waived
}
