package com.astarworks.astera.weapons.application.service

import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ILoadoutRepository] used when the persistence plugin is not loaded.
 *
 * Wave 4.5 (R3.5) / ADR-0027: registered by
 * [com.astarworks.astera.weapons.platform.AsteraWeaponsPlugin] at the default
 * priority (0). When Astera-Persistence boots, its [PostgresLoadoutRepository]
 * overlays this at priority 100 (cross-plugin `KClass` identity is preserved
 * because [ILoadoutRepository] lives in `plugin/core`).
 *
 * # Thread safety
 *
 * Backed by [ConcurrentHashMap] because the writers — the
 * `LoadoutToggleHandler` running on GUI-intent dispatch and the
 * `MatchStartLoadoutApplyListener` running on the event bus — may not always
 * be on the same dispatcher per [com.astarworks.astera.core.application.port.outbound.IScheduler]'s
 * contract. Lookups in the match-start path race writes from the toggle path
 * cheaply, mirroring [com.astarworks.astera.core.application.service.InMemoryGuiRegistry].
 *
 * # Replacement semantics
 *
 * [save] is last-write-wins per slot: the [Loadout] aggregate is stored
 * wholesale, so the latest `save` for a given player fully replaces any prior
 * state. This matches the Postgres overlay's atomic DELETE+INSERT semantics.
 */
public class InMemoryLoadoutRepository : ILoadoutRepository {

    private val store: ConcurrentHashMap<PlayerId, Loadout> = ConcurrentHashMap()

    override fun find(playerId: PlayerId): Loadout? = store[playerId]

    override fun save(playerId: PlayerId, loadout: Loadout) {
        store[playerId] = loadout
    }

    override fun all(): Collection<Pair<PlayerId, Loadout>> =
        store.entries.map { (id, loadout) -> id to loadout }

    /** Drop every persisted loadout. Reserved for tests and Phase 4 hot-reload. */
    public fun clear() {
        store.clear()
    }
}
