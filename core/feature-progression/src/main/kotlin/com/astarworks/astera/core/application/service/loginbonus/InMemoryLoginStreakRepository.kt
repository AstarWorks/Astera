package com.astarworks.astera.core.application.service.loginbonus

import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository
import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ILoginStreakRepository] (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * Default implementation registered at Core onEnable. Phase 2 will add a
 * Postgres-backed variant in `:plugin:persistence` (priority 100) that
 * shadows this one without code changes here.
 */
public class InMemoryLoginStreakRepository : ILoginStreakRepository {

    private val byPlayer: ConcurrentHashMap<PlayerId, LoginStreakRecord> = ConcurrentHashMap()

    override suspend fun findByPlayer(playerId: PlayerId): LoginStreakRecord? = byPlayer[playerId]

    override suspend fun upsert(record: LoginStreakRecord) {
        byPlayer[record.playerId] = record
    }
}
