package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: persistent storage for player [Loadout]s.
 *
 * Wave 4.5 (R3.5, ADR-0027): per-player loadout assignments survive server
 * restarts. The in-memory default ([priority = 0] in plugin/weapons) keeps
 * Wave 4's demo behavior; the Postgres-backed impl (priority = 100 in
 * plugin/persistence) overlays once `ASTERA_PG_*` env vars are set, following
 * the Wave 3 (ADR-0020) overlay pattern.
 *
 * Lives in `plugin/core` so the [Loadout] / [PlayerId] / [com.astarworks.astera.core.domain.item.ItemId]
 * `KClass` identity is consistent across plugins (Wave 3 §"Cross-plugin
 * KClass identity"). Without this, the persistence plugin's `register` and
 * the weapons plugin's `get<ILoadoutRepository>()` would resolve to different
 * `Class` objects and the lookup would silently miss.
 */
public interface ILoadoutRepository {
    /** Returns the persisted loadout for [playerId], or null if none stored. */
    public fun find(playerId: PlayerId): Loadout?

    /** Upsert: store [loadout] under [playerId]. Overwrites prior state. */
    public fun save(playerId: PlayerId, loadout: Loadout)

    /** Snapshot of every persisted loadout. Useful for tests and audits. */
    public fun all(): Collection<Pair<PlayerId, Loadout>>
}
