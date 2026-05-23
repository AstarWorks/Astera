package com.astarworks.astera.stats.application.service.nameplate

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.nameplate.NameplateBadge
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-player [NameplateBadge] cache (Wave 5flex / ADR-0102
 * surface #2).
 *
 * Wave 5flex does not persist the badge — the cache is invalidated on
 * server restart and rebuilt the first time the player joins. Long-term
 * persistence would mostly waste bytes: the badge is a *projection* of
 * other state (Title / Certification / PowerIndex / Achievement) that
 * is already persisted in their own plugins.
 *
 * Concurrency: cached badges are written from two threads — the join
 * listener (main thread) and the `PowerIndexComputed` event handler
 * (asynchronous task). ConcurrentHashMap is the simplest correct fit
 * and the registry is read-heavy (every tablist refresh hits it).
 */
public class NameplateBadgeRegistry {

    private val cache = ConcurrentHashMap<PlayerId, NameplateBadge>()

    /** Current badge for [playerId]. `null` iff never computed (or invalidated). */
    public fun find(playerId: PlayerId): NameplateBadge? = cache[playerId]

    /** Read with a fallback empty badge when nothing is cached. */
    public fun resolveOrEmpty(playerId: PlayerId): NameplateBadge =
        cache[playerId] ?: NameplateBadge.empty(playerId)

    /** Upsert. */
    public fun put(badge: NameplateBadge) {
        cache[badge.playerId] = badge
    }

    /** Drop the cached badge (e.g. on player quit). */
    public fun evict(playerId: PlayerId) {
        cache.remove(playerId)
    }

    /** Snapshot for ops dashboards. */
    public fun snapshot(): Map<PlayerId, NameplateBadge> = cache.toMap()
}
