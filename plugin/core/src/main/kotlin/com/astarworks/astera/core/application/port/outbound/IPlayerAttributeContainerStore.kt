package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: per-player attribute container store (Wave 5e, ADR-0058 §5).
 *
 * Canonical home for player resources — `Wallet`, `PlayerStats`, and
 * `ManaPool` are facades over [containerFor].
 *
 * The InMemory default (`InMemoryPlayerAttributeContainer` in core,
 * priority 0) lazy-creates per-player containers; the Postgres overlay
 * (`PostgresPlayerAttributeContainer` in persistence, priority 100)
 * reads/writes the V14 `player_attributes` JSONB row.
 *
 * Threading: same contract as [IAttributeContainer] — main-thread only.
 */
public interface IPlayerAttributeContainerStore {

    /**
     * Get-or-create the container for [playerId]. Calls within a single
     * server lifetime are stable: the same `(playerId, store-instance)`
     * always returns the same container reference.
     */
    public fun containerFor(playerId: PlayerId): IAttributeContainer

    /**
     * Force-persist any pending state for [playerId]. Hint to the
     * Postgres overlay; in-memory impls are no-op.
     */
    public fun flush(playerId: PlayerId)

    /**
     * Snapshot of every currently-loaded container. Audit / debug only.
     * Postgres impl returns only the rows it has loaded since
     * `containerFor` — not the full table.
     */
    public fun loaded(): Map<PlayerId, IAttributeContainer>
}
