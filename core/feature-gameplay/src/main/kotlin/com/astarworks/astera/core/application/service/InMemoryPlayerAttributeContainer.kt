package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default in-memory [IPlayerAttributeContainerStore] (Wave 5e, ADR-0058 §5).
 *
 * Lazy-creates one [SimpleAttributeContainer] per [PlayerId]; the Wave 5e
 * version IS the canonical storage (no parallel `Map<PlayerId, …>` lives
 * elsewhere). All `STAT_*` / `WALLET_*` / `MANA_*` keys for a player land
 * in the same container instance — `Wallet`, `PlayerStats`, and `ManaPool`
 * share it.
 *
 * Threading: same as [IAttributeContainer] — main-thread only.
 */
public class InMemoryPlayerAttributeContainer : IPlayerAttributeContainerStore {

    private val containers: MutableMap<PlayerId, SimpleAttributeContainer> = mutableMapOf()

    override fun containerFor(playerId: PlayerId): IAttributeContainer =
        containers.getOrPut(playerId) { SimpleAttributeContainer() }

    override fun flush(playerId: PlayerId) {
        // no-op for in-memory; Postgres overlay flushes JSONB row.
    }

    override fun reload(playerId: PlayerId) {
        // no-op: this JVM's map is the only copy.
    }

    override fun loaded(): Map<PlayerId, IAttributeContainer> = containers.toMap()
}
