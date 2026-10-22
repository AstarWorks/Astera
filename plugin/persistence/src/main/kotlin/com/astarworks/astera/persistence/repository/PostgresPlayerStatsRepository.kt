package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats

/**
 * Postgres-backed [IPlayerStatsRepository]. Wave 5e (ADR-0058 §5): shrank
 * to a thin facade over [IPlayerAttributeContainerStore] — the V1
 * `player_stats` columnar table is no longer read; the V14
 * `player_attributes` JSONB row is canonical.
 *
 * `find` always returns a non-null [PlayerStats] in the new world, because
 * the container store creates an entry on first access. To honor the
 * existing `find(...): PlayerStats?` contract (callers in
 * `RewardOnDamageListener` / use cases rely on the null path to mean
 * "player has never been recorded"), this impl checks whether the container
 * store has any **loaded** state for the player and returns `null` only
 * when the underlying row+cache are both empty.
 *
 * `upsert` also accepts detached stats (including the first mutation after a
 * cold miss). Copy their values into the canonical write-through container
 * before flushing; flushing an untouched cached container would lose them.
 */
public class PostgresPlayerStatsRepository(
    private val containerStore: IPlayerAttributeContainerStore,
) : IPlayerStatsRepository {

    override fun find(playerId: PlayerId): PlayerStats? {
        val container = containerStore.containerFor(playerId)
        // If the load was a cold miss and no keys exist, treat as "not recorded".
        if (container.keys().none { it.namespaced.startsWith("stat.") }) return null
        return PlayerStats(playerId, container)
    }

    override fun upsert(stats: PlayerStats): PlayerStats {
        val target = containerStore.containerFor(stats.playerId)
        if (target !== stats.container) {
            stats.container.keys().forEach { key ->
                target.set(key, stats.container.get(key)!!)
            }
        }
        containerStore.flush(stats.playerId)
        return PlayerStats(stats.playerId, target)
    }

    /**
     * Wave 5e: enumerating every loaded container is the closest analogue.
     * The V14 row may contain players that haven't been touched this server
     * lifetime; callers that need that full set must iterate the table
     * directly (Phase 2 admin tooling).
     */
    override fun all(): Collection<PlayerStats> =
        containerStore.loaded().map { (id, container) -> PlayerStats(id, container) }
}
