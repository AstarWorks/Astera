package com.astarworks.astera.core.domain.loginbonus

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.LocalDate

/**
 * Per-player login-streak state (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * Phase 1 stores this in [com.astarworks.astera.core.application.service.loginbonus.InMemoryLoginStreakRepository]
 * only — no Postgres migration ships in this wave. Phase 2 will persist via a
 * dedicated table (planned V44+) and the in-memory repo will be left as a test
 * fake.
 */
public data class LoginStreakRecord(
    public val playerId: PlayerId,
    public val streakDays: Int,
    public val lastClaimDate: LocalDate,
) {
    init {
        require(streakDays >= 0) { "streakDays must be >= 0 (got $streakDays)" }
    }
}
