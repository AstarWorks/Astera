package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile

/**
 * In-memory [IPlayerRepository] for use case tests.
 *
 * Backed by a `PlayerId → PlayerProfile` map. [findByDisplayName] does a
 * linear scan — fine for the small player counts test scenarios construct.
 *
 * Production-quality enough that feature tests can exercise the full
 * profile flow (save → look up by id → look up by name → delete) without
 * a Postgres harness. The Phase 2-mid `adapter-persistence-postgres` impl
 * supersedes this for real deployments.
 */
public class FakePlayerRepository : IPlayerRepository {

    private val store: MutableMap<PlayerId, PlayerProfile> = linkedMapOf()

    override fun findById(id: PlayerId): PlayerProfile? = store[id]

    override fun all(): Collection<PlayerProfile> = store.values.toList()

    override fun save(entity: PlayerProfile) {
        store[entity.id] = entity
    }

    override fun delete(id: PlayerId) {
        store.remove(id)
    }

    override fun findByDisplayName(name: String): PlayerProfile? =
        store.values.firstOrNull { it.displayName == name }
}
