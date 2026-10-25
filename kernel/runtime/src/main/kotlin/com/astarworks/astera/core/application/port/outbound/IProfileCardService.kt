package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.profile.PublicProfileCard

/**
 * Outbound port: load and persist [PublicProfileCard] snapshots
 * (Wave 5flex / ADR-0102 surface #3).
 *
 * Default impl `InMemoryProfileCardService` (priority 0) registered by
 * `AsteraCorePlugin`; Postgres overlay `PostgresProfileCardService`
 * (V35) registered by `AsteraPersistencePlugin` at priority 100.
 *
 * # Read semantics
 *
 * [find] returns the *latest* snapshot. If a player's settings have
 * the profile flagged
 * [com.astarworks.astera.core.domain.profile.ProfileVisibility.PRIVATE],
 * the snapshot is still returned through this port — the REST adapter
 * (Wave 8b) checks visibility before serialising.
 *
 * # Write semantics
 *
 * [save] upserts on [PlayerId]; the snapshot computed by
 * `RecomputeProfileCardUseCase` (Wave 5flex Phase B) is overwriting the
 * previous one. There is no audit history at this layer (Web admin
 * dashboards own that via Prometheus / `engagement_snapshots`).
 *
 * [delete] is for GDPR-style removal requests + tests.
 */
public interface IProfileCardService {

    public fun find(playerId: PlayerId): PublicProfileCard?

    public fun save(card: PublicProfileCard)

    public fun delete(playerId: PlayerId)

    /** Snapshot of every card. Used by Web admin landing page. */
    public fun all(): List<PublicProfileCard>
}
