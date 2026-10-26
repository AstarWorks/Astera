package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: persistent storage for player Norma (mission) progress.
 *
 * Wave 4.5 (R3.5, ADR-0027): mission progress survives server restarts so
 * dailies / weeklies actually mean something. Per Wave 3 cross-plugin
 * classloader rule, this port + [MissionProgress] + [NormaId] all live in
 * `plugin/core`.
 *
 * In-memory default lives in plugin/stats at priority 0; Postgres overlay
 * in plugin/persistence at priority 100. `InMemoryNormaService` delegates
 * progress reads/writes to this repository after Wave 4.5.
 */
public interface INormaProgressRepository {
    /** Returns the persisted progress entry for ([playerId], [normaId]), or null. */
    public suspend fun find(playerId: PlayerId, normaId: NormaId): MissionProgress?

    /** Upsert progress. `progress.playerId` must match [playerId] (defensive). */
    public suspend fun upsert(playerId: PlayerId, progress: MissionProgress)

    /** Every progress entry for [playerId] across all normas. */
    public suspend fun allFor(playerId: PlayerId): Collection<MissionProgress>
}
