package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.match.MatchWorld

/**
 * Outbound port: per-match world lookup (Wave 5d).
 *
 * Externalizes `MatchWorld` from `core.domain.match.Match`. Each active
 * match owns at most one `MatchWorld`; `CreateMatchWorldUseCase` subscribes
 * to `MatchStarted` to populate, `DestroyMatchWorldUseCase` subscribes to
 * `MatchEnded` to clean up.
 *
 * Wave 5d ships `InMemoryMatchWorldRepository` (priority 0). Persistence
 * impl (when world persistence is required) is a future wave.
 */
public interface IMatchWorldRepository {
    public fun findByMatch(matchId: MatchId): MatchWorld?
    public fun save(matchWorld: MatchWorld)
    public fun remove(matchId: MatchId)
    public fun all(): List<MatchWorld>
}
