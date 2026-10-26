package com.astarworks.astera.core.application.port.outbound.loginbonus

import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player login streak storage
 * (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * Phase 1 ships [com.astarworks.astera.core.application.service.loginbonus.InMemoryLoginStreakRepository].
 * Phase 2 will add a Postgres-backed implementation in `:plugin:persistence`
 * with a new migration (planned V44+); no migration ships in this wave.
 */
public interface ILoginStreakRepository {
    /** Return the player's record, or `null` if they have never claimed. */
    public fun findByPlayer(playerId: PlayerId): LoginStreakRecord?

    /** Insert or update [record]; the player id inside identifies the row. */
    public fun upsert(record: LoginStreakRecord)
}
